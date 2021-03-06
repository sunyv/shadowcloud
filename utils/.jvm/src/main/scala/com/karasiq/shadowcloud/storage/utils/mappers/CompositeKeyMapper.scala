package com.karasiq.shadowcloud.storage.utils.mappers

import akka.util.ByteString
import com.typesafe.config.Config

import com.karasiq.common.configs.ConfigImplicits
import com.karasiq.shadowcloud.model.{Chunk, ChunkId}
import com.karasiq.shadowcloud.storage.utils.ChunkKeyMapper

private object CompositeKeyMapper {
  sealed trait Strategy extends ((ChunkId, ChunkId) ⇒ ChunkId)

  object Strategy {
    case object Concat extends Strategy {
      override def apply(v1: ChunkId, v2: ChunkId): ChunkId = v1 ++ v2
    }

    case object XOR extends Strategy {
      override def apply(v1: ChunkId, v2: ChunkId): ChunkId = {
        val bsb = ByteString.newBuilder
        val (data, key) = if (v1.length >= v2.length) (v1, v2) else (v2, v1)
        bsb.sizeHint(data.length)

        for (dataIndex ← data.indices; keyIndex = dataIndex % key.length) {
          bsb += (data(dataIndex) ^ key(keyIndex)).toByte
        }
        bsb.result()
      }
    }

    def forName(str: String): Strategy = str.toLowerCase match {
      case "concat" ⇒ Concat
      case "xor" ⇒ XOR
      case _ ⇒ throw new IllegalArgumentException(str)
    }
  }
}

private[shadowcloud] class CompositeKeyMapper(config: Config) extends ChunkKeyMapper {
  import CompositeKeyMapper._

  private[this] object settings extends ConfigImplicits {
    val strategy = Strategy.forName(config.withDefault("concat", _.getString("strategy")))
    val mappers = config.getStrings("mappers")
  }

  require(settings.mappers.nonEmpty, "No mappers specified")
  private[this] val mapperInstances = settings.mappers.map(name ⇒ ChunkKeyMapper.forName(name, config))

  def apply(chunk: Chunk): ChunkId = {
    mapperInstances.map(_(chunk)).reduce(settings.strategy(_, _))
  }
}
