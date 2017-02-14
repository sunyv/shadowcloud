package com.karasiq.shadowcloud.actors

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.pattern.ask
import akka.util.Timeout
import com.karasiq.shadowcloud.actors.ChunkIODispatcher.{ReadChunk, WriteChunk}
import com.karasiq.shadowcloud.actors.events.RegionEvent.RegionEnvelope
import com.karasiq.shadowcloud.actors.events.StorageEvent.StorageEnvelope
import com.karasiq.shadowcloud.actors.events.{RegionEvent, StorageEvent}
import com.karasiq.shadowcloud.actors.internal.{ChunksTracker, StorageTracker}
import com.karasiq.shadowcloud.index.IndexDiff
import com.karasiq.shadowcloud.storage.IndexMerger
import com.karasiq.shadowcloud.storage.IndexMerger.RegionKey

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

object VirtualRegionDispatcher {
  // Messages
  sealed trait Message
  case class Register(storageId: String, dispatcher: ActorRef) extends Message

  // Internal messages
  private case class PushDiffs(storageId: String, diffs: Seq[(Long, IndexDiff)]) extends Message

  // Props 
  def props(regionId: String): Props = {
    Props(classOf[VirtualRegionDispatcher], regionId)
  }
}

class VirtualRegionDispatcher(regionId: String) extends Actor with ActorLogging {
  import VirtualRegionDispatcher._
  require(regionId.nonEmpty)
  implicit val executionContext: ExecutionContext = context.dispatcher
  implicit val timeout = Timeout(10 seconds)
  val storages = new StorageTracker
  val chunks = new ChunksTracker(storages, log)
  val merger = IndexMerger.region

  def receive: Receive = {
    // -----------------------------------------------------------------------
    // TODO: Folder commands
    // -----------------------------------------------------------------------
    // ???

    // -----------------------------------------------------------------------
    // Read/write commands
    // -----------------------------------------------------------------------
    case ReadChunk(chunk) ⇒
      chunks.readChunk(chunk, sender())

    case WriteChunk(chunk) ⇒
      chunks.writeChunk(chunk, sender())

    case _: WriteChunk.Success ⇒
      // Ignore

    case WriteChunk.Failure(chunk, error) ⇒
      log.error(error, "Chunk write failed: {}", chunk)
      chunks.unregisterChunk(sender(), chunk)
      chunks.retryPendingChunks()

    // -----------------------------------------------------------------------
    // Storage events
    // -----------------------------------------------------------------------
    case Register(storageId, dispatcher) if !storages.contains(storageId) ⇒
      log.info("Registered storage: {}", dispatcher)
      storages.register(storageId, dispatcher)
      val indexFuture = (dispatcher ? IndexSynchronizer.GetIndex).mapTo[IndexSynchronizer.GetIndex.Success]
      indexFuture.onComplete {
        case Success(IndexSynchronizer.GetIndex.Success(diffs)) ⇒
          self ! PushDiffs(storageId, diffs)

        case Failure(error) ⇒
          log.error(error, "Error fetching index: {}", dispatcher)
      }

    case PushDiffs(storageId, diffs) if storages.contains(storageId) ⇒
      addStorageDiffs(storageId, diffs)
      chunks.retryPendingChunks()

    case StorageEnvelope(storageId, event) if storages.contains(storageId) ⇒ event match {
      case StorageEvent.IndexLoaded(diffs) ⇒
        log.info("Storage [{}] index loaded: {} diffs", storageId, diffs.length)
        dropStorageDiffs(storageId)
        addStorageDiffs(storageId, diffs)

      case StorageEvent.IndexUpdated(sequenceNr, diff, _) ⇒
        log.info("Storage [{}] index updated: {}", storageId, diff)
        addStorageDiff(storageId, sequenceNr, diff)

      case StorageEvent.PendingIndexUpdated(_) ⇒
        // Ignore

      case StorageEvent.ChunkWritten(chunk) ⇒
        log.info("Chunk written: {}", chunk)
        chunks.registerChunk(storages.getDispatcher(storageId), chunk)
        RegionEvent.stream.publish(RegionEnvelope(regionId, RegionEvent.ChunkWritten(storageId, chunk)))
    }

    case Terminated(dispatcher) ⇒
      log.debug("Watched actor terminated: {}", dispatcher)
      if (storages.contains(dispatcher)) {
        val storageId = storages.getStorageId(dispatcher)
        dropStorageDiffs(storageId)
        storages.unregister(dispatcher)
      }
      chunks.unregister(dispatcher)
  }

  private[this] def addStorageDiffs(storageId: String, diffs: Seq[(Long, IndexDiff)]): Unit = {
    val dispatcher = storages.getDispatcher(storageId)
    diffs.foreach { case (sequenceNr, diff) ⇒
      chunks.update(dispatcher, diff.chunks)
      val regionKey = RegionKey(diff.time, storageId, sequenceNr)
      merger.add(regionKey, diff)
      RegionEvent.stream.publish(RegionEnvelope(regionId, RegionEvent.IndexUpdated(regionKey, diff)))
    }
  }

  @inline private[this] def addStorageDiff(storageId: String, sequenceNr: Long, diff: IndexDiff) = {
    addStorageDiffs(storageId, Array((sequenceNr, diff)))
  }

  private[this] def dropStorageDiffs(storageId: String): Unit = {
    merger.remove(merger.diffs.keySet.toSet.filter(_.indexId == storageId))
  }

  override def postStop(): Unit = {
    StorageEvent.stream.unsubscribe(self)
    super.postStop()
  }
}
