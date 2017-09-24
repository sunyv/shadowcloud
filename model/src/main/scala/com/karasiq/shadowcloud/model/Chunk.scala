package com.karasiq.shadowcloud.model

import scala.language.postfixOps

import com.karasiq.common.memory.MemorySize
import com.karasiq.shadowcloud.index.utils.{HasEmpty, HasWithoutData, HasWithoutKeys}
import com.karasiq.shadowcloud.model.crypto.EncryptionParameters

@SerialVersionUID(0L)
final case class Chunk(checksum: Checksum = Checksum.empty,
                       encryption: EncryptionParameters = EncryptionParameters.empty,
                       data: Data = Data.empty) extends SCEntity with HasEmpty with HasWithoutData with HasWithoutKeys {
  type Repr = Chunk
  
  def isEmpty: Boolean = {
    data.isEmpty
  }

  def withoutData: Chunk = {
    copy(data = Data.empty)
  }

  def withoutKeys = {
    copy(encryption = encryption.withoutKeys)
  }

  override def hashCode(): Int = {
    checksum.hashCode()
  }

  override def equals(obj: scala.Any): Boolean = obj match {
    case ch: Chunk ⇒
      checksum == ch.checksum && encryption == ch.encryption

    case _ ⇒
      false
  }

  override def toString: String = {
    s"Chunk($checksum, $encryption, ${MemorySize(data.plain.length)}/${MemorySize(data.encrypted.length)})"
  }
}