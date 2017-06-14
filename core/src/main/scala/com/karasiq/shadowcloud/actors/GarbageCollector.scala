package com.karasiq.shadowcloud.actors

import java.util.concurrent.TimeoutException

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.{higherKinds, postfixOps}
import scala.util.{Failure, Success}

import akka.actor.{Actor, ActorLogging, ActorRef, NotInfluenceReceiveTimeout, Props, ReceiveTimeout}
import akka.pattern.{ask, pipe}
import akka.util.{ByteString, Timeout}

import com.karasiq.shadowcloud.ShadowCloud
import com.karasiq.shadowcloud.actors.internal.GarbageCollectUtil
import com.karasiq.shadowcloud.actors.ChunkIODispatcher.ChunkPath
import com.karasiq.shadowcloud.actors.utils.{GCState, MessageStatus}
import com.karasiq.shadowcloud.index.Chunk
import com.karasiq.shadowcloud.index.diffs.IndexDiff
import com.karasiq.shadowcloud.storage.StorageIOResult
import com.karasiq.shadowcloud.storage.utils.{IndexMerger, StorageUtils}
import com.karasiq.shadowcloud.storage.utils.IndexMerger.RegionKey
import com.karasiq.shadowcloud.utils.{MemorySize, Utils}

object GarbageCollector {
  // Messages
  sealed trait Message
  case class CollectGarbage(startNow: Boolean = false, delete: Option[Boolean] = None) extends Message with NotInfluenceReceiveTimeout
  object CollectGarbage extends MessageStatus[String, Map[String, GCState]]
  case class Defer(time: FiniteDuration) extends Message with NotInfluenceReceiveTimeout

  // Props
  def props(storageId: String, index: ActorRef, chunkIO: ActorRef): Props = {
    Props(classOf[GarbageCollector], storageId, index, chunkIO)
  }
}

private final class GarbageCollector(storageId: String, index: ActorRef, chunkIO: ActorRef) extends Actor with ActorLogging {
  import context.dispatcher

  import GarbageCollector._

  // -----------------------------------------------------------------------
  // Context
  // -----------------------------------------------------------------------
  private[this] implicit val timeout = Timeout(30 seconds)
  private[this] val sc = ShadowCloud()
  private[this] val config = sc.storageConfig(storageId)
  private[this] val gcSchedule = context.system.scheduler.schedule(5 minutes, 5 minutes, self, CollectGarbage())
  private[this] var gcDeadline = Deadline.now

  // -----------------------------------------------------------------------
  // Receive
  // -----------------------------------------------------------------------
  def receiveIdle: Receive = {
    case CollectGarbage(startNow, delete) ⇒
      if (startNow || gcDeadline.isOverdue()) {
        log.debug("Starting garbage collection")

        context.setReceiveTimeout(10 minutes)
        context.become(receiveCollecting(Set(sender()) - self - Actor.noSender))

        val future = runGarbageCollection(delete.getOrElse(config.gcAutoDelete))
        CollectGarbage.wrapFuture(storageId, future).pipeTo(self)
      } else {
        log.debug("Garbage collection will be started in {} minutes", gcDeadline.timeLeft.toMinutes)
      }

    case Defer(time) ⇒
      if (gcDeadline.timeLeft < time) {
        log.debug("GC deferred to {}", time.toCoarsest)
        gcDeadline = time.fromNow
      }
  }

  def receiveCollecting(receivers: Set[ActorRef]): Receive = {
    case CollectGarbage(_, _) ⇒
      val ref = sender()
      if (ref != self && ref != Actor.noSender) {
        context.become(receiveCollecting(receivers + ref))
      }

    case s: CollectGarbage.Status ⇒
      receivers.foreach(_ ! s)
      context.setReceiveTimeout(Duration.Undefined)
      context.become(receiveIdle)
      self ! Defer(30 minutes)

    case ReceiveTimeout ⇒
      throw new TimeoutException("GC timeout")
  }

  override def receive: Receive = receiveIdle

  override def postStop(): Unit = {
    gcSchedule.cancel()
    super.postStop()
  }

  // -----------------------------------------------------------------------
  // Orphaned chunks deletion
  // -----------------------------------------------------------------------
  private[this] def runGarbageCollection(delete: Boolean): Future[Map[String, GCState]] = {
    def collectOrphanedChunks(): Future[Map[String, GCState]] = {
      def getChunkKeys: Future[Set[ChunkPath]] = {
        ChunkIODispatcher.GetKeys.unwrapFuture(chunkIO ? ChunkIODispatcher.GetKeys)
      }

      val keys = getChunkKeys.map(_.groupBy(_.region).mapValues(_.map(_.id)))
      val regionIndexes = keys.flatMap { keysByRegion ⇒
        Future.sequence(keysByRegion.toList.map { case (region, keys) ⇒
          sc.ops.region.getIndex(region).map((region, keys, _))
        })
      }

      regionIndexes.map { indexes ⇒
        val gcUtil = GarbageCollectUtil(config)
        indexes.map { case (region, keys, state) ⇒
          region → gcUtil.collect(IndexMerger.restore(RegionKey.zero, state), keys)
        }.filter(_._2.nonEmpty).toMap
      }
    }

    def toDeleteFromStorage(state: GCState): Set[ByteString] = {
      @inline def keys(chunks: Set[Chunk]): Set[ByteString] = chunks.map(config.chunkKey)

      keys(state.orphaned) ++ state.notIndexed
    }

    def toDeleteFromIndex(state: GCState): Set[Chunk] = {
      state.orphaned ++ state.notExisting
    }

    def deleteChunksFromStorage(chunks: Map[String, Set[ByteString]]): Future[StorageIOResult] = {
      val futures = chunks.map { case (region, chunks) ⇒
        val paths = chunks.map(ChunkPath(region, _))
        if (log.isDebugEnabled) log.debug("Deleting chunks from storage {}: [{}]", region, Utils.printHashes(chunks))
        ChunkIODispatcher.DeleteChunks.unwrapFuture(chunkIO ? ChunkIODispatcher.DeleteChunks(paths))
      }
      StorageUtils.foldIOFutures(futures.toSeq: _*)
    }

    def deleteChunksFromIndex(chunks: Map[String, Set[Chunk]]): Unit = {
      chunks.foreach { case (region, chunks) ⇒
        if (log.isDebugEnabled) log.debug("Deleting chunks from index {}: [{}]", region, Utils.printChunkHashes(chunks))
        index ! IndexDispatcher.AddPending(region, IndexDiff.deleteChunks(chunks.toSeq: _*))
      }
    }

    val future = collectOrphanedChunks()
    future.onComplete {
      case Success(gcStates) ⇒
        if (gcStates.nonEmpty) {
          val toDelete = gcStates.mapValues(toDeleteFromStorage)
          val toUnIndex = gcStates.mapValues(toDeleteFromIndex).filter(_._2.nonEmpty)
          if (delete) {
            log.warning("Deleting orphaned chunks: {}", gcStates)
            deleteChunksFromStorage(toDelete).onComplete {
              case Success(result) ⇒
                deleteChunksFromIndex(toUnIndex)
                if (log.isInfoEnabled) log.info("Orphaned chunks deleted: {}", MemorySize.toString(result.count))

              case Failure(error) ⇒
                log.error(error, "Error deleting orphaned chunks")
            }
          } else {
            if (log.isWarningEnabled) {
              // val hashes = toDelete.values.flatten ++ toUnIndex.values.flatten.map(config.chunkKey)
              // if (hashes.nonEmpty) log.warning("Chunks to delete: [{}]", Utils.printHashes(hashes.toSet, 50))
              val toDeleteCount = toDelete.values.flatten.size
              val toUnIndexCount = toUnIndex.values.flatten.size
              log.info("Found {} chunks to delete, {} to un-index", toDeleteCount, toUnIndexCount)
            }
          }
        }

      case Failure(error) ⇒
        log.error(error, "Garbage collection failed")
    }
    future
  }
}
