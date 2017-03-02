package com.karasiq.shadowcloud.utils

import akka.Done
import akka.stream.IOResult
import akka.util.ByteString
import com.karasiq.shadowcloud.index.Chunk
import org.apache.commons.codec.binary.Hex

import scala.collection.TraversableLike
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.language.{higherKinds, postfixOps}
import scala.util.{Failure, Success, Try}

private[shadowcloud] object Utils {
  // -----------------------------------------------------------------------
  // Hex strings
  // -----------------------------------------------------------------------
  def toHexString(bs: ByteString): String = {
    Hex.encodeHexString(bs.toArray)
  }

  def parseHexString(hexString: String): ByteString = {
    ByteString(Hex.decodeHex(hexString.toCharArray))
  }

  // -----------------------------------------------------------------------
  // Time
  // -----------------------------------------------------------------------
  @inline
  def timestamp: Long = {
    System.currentTimeMillis()
  }

  @inline
  def toScalaDuration(duration: java.time.Duration): FiniteDuration = {
    FiniteDuration(duration.toNanos, scala.concurrent.duration.NANOSECONDS)
  }

  // -----------------------------------------------------------------------
  // toString() utils
  // -----------------------------------------------------------------------
  def printHashes(chunks: Traversable[Chunk], limit: Int = 20): String = {
    val size = chunks.size
    val sb = new StringBuilder(math.min(limit, size) * 22 + 10)
    chunks.take(limit).foreach { chunk ⇒
      if (chunk.checksum.hash.nonEmpty) {
        if (sb.nonEmpty) sb.append(", ")
        sb.append(toHexString(chunk.checksum.hash))
      }
    }
    if (size > limit) sb.append(", (").append(size - limit).append(" more)")
    sb.result()
  }

  // -----------------------------------------------------------------------
  // Futures
  // -----------------------------------------------------------------------
  def unwrapIOResult(future: Future[IOResult])(implicit ec: ExecutionContext): Future[Long] = {
    future
      .recover { case error ⇒ IOResult(0, Failure(error)) }
      .map {
        case IOResult(written, Success(Done)) ⇒ written
        case IOResult(_, Failure(error)) ⇒ throw error
      }
  }

  def onIOComplete(future: Future[IOResult])(pf: PartialFunction[Try[Long], Unit])(implicit ec: ExecutionContext): Unit = {
    unwrapIOResult(future).onComplete(pf)
  }

  // -----------------------------------------------------------------------
  // Misc
  // -----------------------------------------------------------------------
  @inline
  def isSameChunk(chunk: Chunk, chunk1: Chunk): Boolean = {
    // chunk.withoutData == chunk1.withoutData
    chunk == chunk1
  }

  @inline
  def takeOrAll[T, Col[`T`] <: TraversableLike[T, Col[T]]](all: Col[T], count: Int): Col[T] = {
    if (count > 0) all.take(count) else all
  }
}