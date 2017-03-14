package com.karasiq.shadowcloud.actors

import akka.actor.{Actor, ActorLogging, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.karasiq.shadowcloud.actors.internal.PendingOperation
import com.karasiq.shadowcloud.actors.utils.MessageStatus
import com.karasiq.shadowcloud.config.AppConfig
import com.karasiq.shadowcloud.index.Chunk
import com.karasiq.shadowcloud.storage.{CategorizedRepository, RepositoryKeys, StorageIOResult}
import com.karasiq.shadowcloud.streams.ByteStringConcat

import scala.language.postfixOps
import scala.util.{Failure, Success}

object ChunkIODispatcher {
  // Messages
  sealed trait Message
  case class WriteChunk(region: String, chunk: Chunk) extends Message
  object WriteChunk extends MessageStatus[(String, Chunk), Chunk]

  case class ReadChunk(region: String, chunk: Chunk) extends Message
  object ReadChunk extends MessageStatus[(String, Chunk), Chunk]

  // Props
  def props(repository: CategorizedRepository[String, String]): Props = {
    Props(classOf[ChunkIODispatcher], repository)
  }
}

private final class ChunkIODispatcher(repository: CategorizedRepository[String, String]) extends Actor with ActorLogging {
  import ChunkIODispatcher._
  import context.dispatcher
  implicit val actorMaterializer = ActorMaterializer()
  val chunksWrite = PendingOperation.withRegionChunk
  val chunksRead = PendingOperation.withRegionChunk
  val config = AppConfig().storage

  def receive: Receive = {
    case WriteChunk(region, chunk) ⇒
      chunksWrite.addWaiter((region, chunk), sender(), () ⇒ writeChunk(region, chunk))

    case ReadChunk(region, chunk) ⇒
      chunksRead.addWaiter((region, chunk), sender(), () ⇒ readChunk(region, chunk))

    case msg: WriteChunk.Status ⇒
      chunksWrite.finish(msg.key, msg)

    case msg: ReadChunk.Status ⇒
      chunksRead.finish(msg.key, msg)
  }

  private[this] def subRepository(region: String) = {
    RepositoryKeys.toHexString(repository.subRepository(region))
  }

  private[this] def writeChunk(region: String, chunk: Chunk): Unit = {
    val key = config.chunkKey(chunk)
    val localRepository = subRepository(region)
    val writeSink = localRepository.write(key)
    val future = Source.single(chunk.data.encrypted).runWith(writeSink)
    future.onComplete {
      case Success(StorageIOResult.Success(path, written)) ⇒
        log.debug("{} bytes written to {}, chunk: {}", written, path, chunk)
        self ! WriteChunk.Success((region, chunk), chunk)

      case Success(StorageIOResult.Failure(path, error)) ⇒
        log.error(error, "Chunk write error to {}: {}", path, chunk)
        self ! WriteChunk.Failure((region, chunk), error)

      case Failure(error) ⇒
        log.error(error, "Chunk write error: {}", chunk)
        self ! WriteChunk.Failure((region, chunk), error)
    }
  }

  private[this] def readChunk(region: String, chunk: Chunk): Unit = {
    val key = config.chunkKey(chunk)
    val localRepository = subRepository(region)
    val readSource = localRepository.read(key)
    val (ioFuture, chunkFuture) = readSource
      .via(ByteStringConcat())
      .map(bs ⇒ chunk.copy(data = chunk.data.copy(encrypted = bs)))
      .toMat(Sink.head)(Keep.both)
      .run()

    ioFuture.onComplete {
      case Success(StorageIOResult.Success(path, bytes)) ⇒
        chunkFuture.onComplete {
          case Success(chunkWithData) ⇒
            log.debug("{} bytes read from {}, chunk: {}", bytes, path, chunkWithData)
            self ! ReadChunk.Success((region, chunk), chunkWithData)

          case Failure(error) ⇒
            log.error(error, "Chunk read error: {}", chunk)
            self ! ReadChunk.Failure((region, chunk), error)
        }

      case Success(StorageIOResult.Failure(path, error)) ⇒
        log.error(error, "Chunk read error from {}: {}", path, chunk)
        self ! ReadChunk.Failure((region, chunk), error)

      case Failure(error) ⇒
        log.error(error, "Chunk read error: {}", chunk)
        self ! ReadChunk.Failure((region, chunk), error)
    }
  }
}
