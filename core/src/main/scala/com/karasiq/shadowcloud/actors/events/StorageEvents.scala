package com.karasiq.shadowcloud.actors.events

import com.karasiq.shadowcloud.index.Chunk
import com.karasiq.shadowcloud.index.diffs.IndexDiff
import com.karasiq.shadowcloud.storage.StorageHealth
import com.karasiq.shadowcloud.storage.utils.IndexMerger

import scala.language.postfixOps

object StorageEvents {
  sealed trait Event
  case class IndexLoaded(diffs: Map[String, IndexMerger.State[Long]]) extends Event
  case class PendingIndexUpdated(region: String, diff: IndexDiff) extends Event
  case class IndexUpdated(region: String, sequenceNr: Long, diff: IndexDiff, remote: Boolean) extends Event
  case class IndexDeleted(region: String, sequenceNrs: Set[Long]) extends Event
  case class ChunkWritten(region: String, chunk: Chunk) extends Event
  case class HealthUpdated(health: StorageHealth) extends Event
}


