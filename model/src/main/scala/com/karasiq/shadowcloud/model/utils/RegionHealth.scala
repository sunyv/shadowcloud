package com.karasiq.shadowcloud.model.utils

import com.karasiq.shadowcloud.model.StorageId

@SerialVersionUID(0L)
final case class RegionHealth(usedSpace: Long, storages: Map[StorageId, StorageHealth]) extends HealthStatus {
  def totalSpace: Long = createSum(_.totalSpace)
  // def usedSpace: Long = createSum(_.usedSpace)
  def freeSpace: Long = createSum(_.freeSpace)
  def writableSpace: Long = createSum(_.writableSpace)

  def online: Boolean = {
    storages.values.exists(_.online)
  }

  def fullyOnline: Boolean = {
    storages.values.forall(_.online)
  }

  def toStorageHealth: StorageHealth = {
    StorageHealth.normalized(writableSpace, totalSpace, usedSpace, online)
  }

  private[this] def createSum(getValue: StorageHealth ⇒ Long): Long = {
    math.max(0L, storages.values.filter(_.online).map(getValue).sum)
  }
}

object RegionHealth {
  val empty = RegionHealth(0L, Map.empty)
}