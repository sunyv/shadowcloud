package com.karasiq.shadowcloud.actors

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

import akka.actor.{Actor, ActorLogging, ActorRef, Kill, PossiblyHarmful, Props, Status, Terminated}
import akka.pattern.{ask, pipe}
import akka.stream.{OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{Sink, Source}

import com.karasiq.shadowcloud.ShadowCloud
import com.karasiq.shadowcloud.actors.ChunkIODispatcher.ChunkPath
import com.karasiq.shadowcloud.actors.context.RegionContext
import com.karasiq.shadowcloud.actors.events.{RegionEvents, StorageEvents}
import com.karasiq.shadowcloud.actors.internal.{ChunksTracker, RegionIndexTracker, StorageTracker}
import com.karasiq.shadowcloud.actors.messages.{RegionEnvelope, StorageEnvelope}
import com.karasiq.shadowcloud.actors.utils.MessageStatus
import com.karasiq.shadowcloud.actors.RegionIndex.SyncReport
import com.karasiq.shadowcloud.config.RegionConfig
import com.karasiq.shadowcloud.exceptions.{RegionException, StorageException}
import com.karasiq.shadowcloud.index.diffs.{FolderIndexDiff, IndexDiff}
import com.karasiq.shadowcloud.model._
import com.karasiq.shadowcloud.storage.StorageHealth
import com.karasiq.shadowcloud.storage.props.StorageProps
import com.karasiq.shadowcloud.storage.replication.{ChunkWriteAffinity, StorageSelector}
import com.karasiq.shadowcloud.storage.replication.ChunkStatusProvider.ChunkStatus
import com.karasiq.shadowcloud.storage.replication.RegionStorageProvider.RegionStorage
import com.karasiq.shadowcloud.storage.utils.IndexMerger
import com.karasiq.shadowcloud.storage.utils.IndexMerger.RegionKey
import com.karasiq.shadowcloud.utils.{AkkaStreamUtils, SizeUnit, Utils}

object RegionDispatcher {
  // Messages
  sealed trait Message
  case class AttachStorage(storageId: StorageId, storageProps: StorageProps,
                           dispatcher: ActorRef, health: StorageHealth = StorageHealth.empty) extends Message
  case class DetachStorage(storageId: StorageId) extends Message
  case object GetStorages extends Message with MessageStatus[String, Seq[RegionStorage]]
  case class GetChunkStatus(chunk: Chunk) extends Message
  object GetChunkStatus extends MessageStatus[Chunk, ChunkStatus]

  case class WriteIndex(diff: FolderIndexDiff) extends Message
  object WriteIndex extends MessageStatus[FolderIndexDiff, IndexDiff]
  case object GetIndex extends Message with MessageStatus[RegionId, IndexMerger.State[RegionKey]]
  case object Synchronize extends Message with MessageStatus[RegionId, Map[StorageId, SyncReport]]
  case class GetFiles(path: Path) extends Message
  object GetFiles extends MessageStatus[Path, Set[File]]
  case class GetFolder(path: Path) extends Message
  object GetFolder extends MessageStatus[Path, Folder]

  case class WriteChunk(chunk: Chunk) extends Message
  case object WriteChunk extends MessageStatus[Chunk, Chunk]
  case class ReadChunk(chunk: Chunk) extends Message
  case object ReadChunk extends MessageStatus[Chunk, Chunk]
  case class RewriteChunk(chunk: Chunk, newAffinity: Option[ChunkWriteAffinity]) extends Message

  // Internal messages
  private[actors] sealed trait InternalMessage extends Message with PossiblyHarmful
  private[actors] case class PushDiffs(storageId: StorageId, diffs: Seq[(SequenceNr, IndexDiff)], pending: IndexDiff) extends InternalMessage
  private[actors] case class PullStorageIndex(storageId: StorageId) extends InternalMessage

  private[actors] case class ChunkReadSuccess(storageId: Option[String], chunk: Chunk) extends InternalMessage
  private[actors] case class ChunkReadFailed(storageId: Option[String], chunk: Chunk, error: Throwable) extends InternalMessage
  private[actors] case class ChunkWriteSuccess(storageId: StorageId, chunk: Chunk) extends InternalMessage
  private[actors] case class ChunkWriteFailed(storageId: StorageId, chunk: Chunk, error: Throwable) extends InternalMessage

  private[actors] case class EnqueueIndexDiff(diff: IndexDiff) extends InternalMessage
  private[actors] case class MarkAsPending(diff: IndexDiff) extends InternalMessage
  private[actors] case class WriteIndexDiff(diff: IndexDiff) extends InternalMessage

  // Props
  def props(regionId: RegionId, regionProps: RegionConfig): Props = {
    Props(new RegionDispatcher(regionId, regionProps))
  }
}

//noinspection TypeAnnotation
private final class RegionDispatcher(regionId: RegionId, regionConfig: RegionConfig) extends Actor with ActorLogging {
  import RegionDispatcher._
  require(regionId.nonEmpty, "Invalid region identifier")

  // -----------------------------------------------------------------------
  // Context
  // -----------------------------------------------------------------------
  private[this] implicit val sc = ShadowCloud()
  import context.dispatcher
  import sc.implicits.{defaultTimeout, materializer}
  
  val storageTracker = StorageTracker()
  val chunksTracker = ChunksTracker(regionId, regionConfig, storageTracker)
  val indexTracker = RegionIndexTracker(regionId, chunksTracker)
  
  private[this] implicit val regionContext = RegionContext(regionId, regionConfig, self, 
    storageTracker, chunksTracker.chunks, indexTracker.globalIndex)
  
  private[this] implicit val storageSelector = StorageSelector.fromClass(regionConfig.storageSelector)

  // -----------------------------------------------------------------------
  // Actors
  // -----------------------------------------------------------------------
  private[this] val gcActor = context.actorOf(RegionGC.props(regionId, regionConfig.garbageCollector), "region-gc")

  // -----------------------------------------------------------------------
  // Streams
  // -----------------------------------------------------------------------
  private[this] val pendingIndexQueue = Source.queue[IndexDiff](sc.config.queues.regionDiffs, OverflowStrategy.dropNew)
    .via(AkkaStreamUtils.groupedOrInstant(sc.config.queues.regionDiffs, sc.config.queues.regionDiffsTime))
    .map(_.fold(IndexDiff.empty)((d1, d2) ⇒ d1.merge(d2)))
    .filter(_.nonEmpty)
    .log("region-grouped-diff")
    .map(WriteIndexDiff)
    .to(Sink.actorRef(self, Kill))
    .run()

  // -----------------------------------------------------------------------
  // Receive
  // -----------------------------------------------------------------------
  def receive: Receive = {
    // -----------------------------------------------------------------------
    // Global index commands
    // -----------------------------------------------------------------------
    case WriteIndex(folders) ⇒
      if (folders.isEmpty) {
        sender() ! WriteIndex.Success(folders, indexTracker.indexes.pending)
        // sender() ! WriteIndex.Failure(folders, RegionException.IndexWriteFailed(IndexDiff(folders = folders), new IllegalArgumentException("Diff is empty")))
      } else {
        log.debug("Index write request: {}", folders)
        val future = (self ? EnqueueIndexDiff(IndexDiff(Utils.timestamp, folders))).mapTo[IndexDiff]
        WriteIndex.wrapFuture(folders, future).pipeTo(sender())
      }

    case EnqueueIndexDiff(diff) ⇒
      log.debug("Enqueuing region index diff: {}", diff)
      val currentSender = sender()
      pendingIndexQueue.offer(diff).onComplete {
        case Success(QueueOfferResult.Enqueued) ⇒
          self.tell(MarkAsPending(diff), currentSender)

        case Success(otherQueueResult) ⇒
          currentSender ! Status.Failure(RegionException.IndexWriteFailed(diff, new IllegalStateException(otherQueueResult.toString)))

        case Failure(error) ⇒
          currentSender ! Status.Failure(RegionException.IndexWriteFailed(diff, error))
      }

    case MarkAsPending(diff) ⇒
      indexTracker.indexes.markAsPending(diff)
      sender() ! Status.Success(indexTracker.indexes.pending)

    case WriteIndexDiff(diff) ⇒
      val storages = indexTracker.storages.io.writeIndex(diff)
      if (storages.isEmpty) {
        // Schedule retry
        schedules.writeDiff(diff)
      }

    case GetFiles(path) ⇒
      val files = indexTracker.indexes.folders
        .get(path.parent)
        .map(_.files.filter(_.path == path))
        .filter(_.nonEmpty)

      files match {
        case Some(files) ⇒
          sender() ! GetFiles.Success(path, files)

        case None ⇒
          sender() ! GetFiles.Failure(path, RegionException.FileNotFound(path))
      }

    case GetFolder(path) ⇒
      indexTracker.indexes.folders.get(path) match {
        case Some(folder) ⇒
          sender() ! GetFolder.Success(path, folder)

        case None ⇒
          sender() ! GetFolder.Failure(path, RegionException.DirectoryNotFound(path))
      }

    case GetIndex ⇒
      sender() ! GetIndex.Success(regionId, indexTracker.indexes.state)

    case Synchronize ⇒
      log.info("Force synchronizing indexes of virtual region: {}", regionId)
      val futures = storageTracker.storages.map { storage ⇒
        indexTracker.storages.io.synchronize(storage).map((storage.id, _))
      }
      val result = Future.sequence(futures).map(_.toMap)
      Synchronize.wrapFuture(regionId, result).pipeTo(sender())

    case GetChunkStatus(chunk) ⇒
      chunksTracker.chunks.getChunkStatus(chunk) match {
        case Some(status) ⇒
          sender() ! GetChunkStatus.Success(chunk, status)

        case None ⇒
          sender() ! GetChunkStatus.Failure(chunk, RegionException.ChunkNotFound(chunk))
      }

    // -----------------------------------------------------------------------
    // Read/write commands
    // -----------------------------------------------------------------------
    case ReadChunk(chunk) ⇒
      chunksTracker.chunkIO.readChunk(chunk, sender())

    case WriteChunk(chunk) ⇒
      chunksTracker.chunkIO.writeChunk(chunk, sender())

    case RewriteChunk(chunk, newAffinity) ⇒
      chunksTracker.chunkIO.repairChunk(chunk, newAffinity, sender())

    case ChunkReadSuccess(storageId, chunk) ⇒
      chunksTracker.storages.callbacks.onReadSuccess(chunk, storageId)

    case ChunkReadFailed(storageId, chunk, error) ⇒
      chunksTracker.storages.callbacks.onReadFailure(chunk, storageId, error)

    case ChunkWriteSuccess(storageId, chunk) ⇒
      chunksTracker.storages.callbacks.onWriteSuccess(chunk, storageId)

    case ChunkWriteFailed(storageId, chunk, error) ⇒
      chunksTracker.storages.callbacks.onWriteFailure(chunk, storageId, error)
      // chunks.retryPendingChunks()

    // -----------------------------------------------------------------------
    // Storage events
    // -----------------------------------------------------------------------
    case AttachStorage(storageId, props, dispatcher, health) ⇒
      val isStorageExists = storageTracker.contains(storageId)
      if (isStorageExists && storageTracker.getDispatcher(storageId) == dispatcher) {
        // Ignore
      } else {
        if (isStorageExists) {
          val oldDispatcher = storageTracker.getDispatcher(storageId)
          log.warning("Replacing storage {} with new dispatcher: {} -> {}", storageId, oldDispatcher, dispatcher)
          indexTracker.storages.state.dropStorageDiffs(storageId)
          storageTracker.unregister(oldDispatcher)
          chunksTracker.storages.state.unregister(oldDispatcher)
        }

        log.info("Registered storage {}: {}", storageId, dispatcher)
        storageTracker.register(storageId, props, dispatcher, health)
        self ! PullStorageIndex(storageId)
      }

    case DetachStorage(storageId) if storageTracker.contains(storageId) ⇒
      val dispatcher = storageTracker.getDispatcher(storageId)
      indexTracker.storages.state.dropStorageDiffs(storageId)
      storageTracker.unregister(dispatcher)
      chunksTracker.storages.state.unregister(dispatcher)

    case GetStorages ⇒
      sender() ! GetStorages.Success(regionId, storageTracker.storages)

    case PullStorageIndex(storageId) if storageTracker.contains(storageId) ⇒
      schedules.deferGC()
      val storage = storageTracker.getStorage(storageId)

      storage.dispatcher ! StorageIndex.OpenIndex(regionId)
      storage.dispatcher ! StorageDispatcher.CheckHealth

      val indexFuture = RegionIndex.GetIndex.unwrapFuture(storage.dispatcher ?
        StorageIndex.Envelope(regionId, RegionIndex.GetIndex))

      indexFuture.onComplete {
        case Success(IndexMerger.State(Nil, IndexDiff.empty)) | Failure(StorageException.NotFound(_)) ⇒
          val diff = indexTracker.indexes.toMergedDiff
          if (diff.nonEmpty) indexTracker.storages.io.writeIndex(storage, diff)

        case Success(IndexMerger.State(diffs, pending)) ⇒
          log.debug("Storage {} index fetched: {} ({})", storageId, diffs, pending)
          self ! PushDiffs(storageId, diffs, pending)

        case Failure(error) ⇒
          log.error(error, "Error fetching index from storage: {}", storageId)
          schedules.pullIndex(storageId)
      }

    case PushDiffs(storageId, diffs, pending) if storageTracker.contains(storageId) ⇒
      indexTracker.indexes.markAsPending(pending)
      indexTracker.storages.state.addStorageDiffs(storageId, diffs)

    case StorageEnvelope(storageId, event: StorageEvents.Event) if storageTracker.contains(storageId) ⇒ event match {
      case StorageEvents.IndexLoaded(`regionId`, state) ⇒
        log.info("Storage [{}] index loaded: {} diffs", storageId, state.diffs.length)
        indexTracker.storages.state.dropStorageDiffs(storageId)
        indexTracker.storages.state.addStorageDiffs(storageId, state.diffs)
        val deletedChunks = {
          val oldIndex = indexTracker.storages.state.extractIndex(storageId)
          val newIndex = IndexMerger.restore(SequenceNr.zero, state)
          newIndex.chunks.diff(oldIndex.chunks).deletedChunks
        }
        deletedChunks.foreach(chunksTracker.storages.state.unregisterChunk(storageId, _))
        schedules.deferGC()

      case StorageEvents.IndexUpdated(`regionId`, sequenceNr, diff, _) ⇒
        log.debug("Storage [{}] index updated: {}", storageId, diff)
        indexTracker.storages.state.addStorageDiff(storageId, sequenceNr, diff)
        schedules.deferGC()

      case StorageEvents.PendingIndexUpdated(`regionId`, diff) ⇒
        log.debug("Storage [{}] pending index updated: {}", storageId, diff)
        // globalIndex.addPending(diff)

      case StorageEvents.IndexDeleted(`regionId`, sequenceNrs) ⇒
        log.debug("Diffs deleted from storage [{}]: {}", storageId, sequenceNrs)
        indexTracker.storages.state.dropStorageDiffs(storageId, sequenceNrs)

      case StorageEvents.ChunkWritten(ChunkPath(`regionId`, _), chunk) ⇒
        log.debug("Chunk written: {}", chunk)
        // chunks.onWriteSuccess(chunk, storageId)
        sc.eventStreams.publishRegionEvent(regionId, RegionEvents.ChunkWritten(storageId, chunk))
        schedules.deferGC()

      case StorageEvents.HealthUpdated(health) ⇒
        log.debug("Storage [{}] health report: {}", storageId, health)
        val wasOffline = {
          val oldHealth = storageTracker.getStorage(storageId).health
          (!oldHealth.online || oldHealth.writableSpace < SizeUnit.MB) &&
            (health.online && health.writableSpace > SizeUnit.MB)
        }
        storageTracker.updateHealth(storageId, health)
        if (wasOffline) chunksTracker.chunkIO.retryPendingChunks()

      case _ ⇒
        // Ignore
    }

    case Terminated(dispatcher) ⇒
      log.debug("Watched actor terminated: {}", dispatcher)
      if (storageTracker.contains(dispatcher)) {
        val storageId = storageTracker.getStorageId(dispatcher)
        indexTracker.storages.state.dropStorageDiffs(storageId)
        storageTracker.unregister(dispatcher)
      }
      chunksTracker.storages.state.unregister(dispatcher)

    // -----------------------------------------------------------------------
    // GC commands
    // -----------------------------------------------------------------------
    case m: RegionGC.Message ⇒
      gcActor.forward(m)
  }

  // -----------------------------------------------------------------------
  // Utils
  // -----------------------------------------------------------------------
  object schedules {
    private[this] val scheduler = context.system.scheduler

    def pullIndex(storageId: StorageId): Unit = {
      scheduler.scheduleOnce(5 seconds, self, PullStorageIndex(storageId))
    }

    def writeDiff(diff: IndexDiff): Unit = {
      scheduler.scheduleOnce(15 seconds, sc.actors.regionSupervisor, RegionEnvelope(regionId, WriteIndexDiff(diff)))
    }

    def deferGC(): Unit = {
      gcActor ! RegionGC.Defer(10 minutes)
    }
  }

  // -----------------------------------------------------------------------
  // Lifecycle
  // -----------------------------------------------------------------------
  override def postStop(): Unit = {
    sc.eventStreams.storage.unsubscribe(self)
    pendingIndexQueue.complete()
    super.postStop()
  }
}
