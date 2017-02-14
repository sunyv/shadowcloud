import java.nio.file.Files

import TestUtils._
import akka.pattern.ask
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestActorRef
import akka.util.ByteString
import com.karasiq.shadowcloud.actors.ChunkIODispatcher.{ReadChunk, WriteChunk}
import com.karasiq.shadowcloud.actors._
import com.karasiq.shadowcloud.actors.events.StorageEvent
import com.karasiq.shadowcloud.actors.events.StorageEvent.StorageEnvelope
import com.karasiq.shadowcloud.crypto.EncryptionMethod
import com.karasiq.shadowcloud.storage.files.{FileChunkRepository, FileIndexRepository}
import org.scalatest.FlatSpecLike

import scala.concurrent.duration._
import scala.language.postfixOps

// Uses local filesystem
class VirtualRegionTest extends ActorSpec with FlatSpecLike {
  val chunk = TestUtils.testChunk
  val index = TestActorRef(IndexSynchronizer.props("testStorage", new FileIndexRepository(Files.createTempDirectory("vrt-index"))), "index")
  val chunkIO = TestActorRef(ChunkIODispatcher.props(new FileChunkRepository(Files.createTempDirectory("vrt-chunks"))), "chunkIO")
  val storage = TestActorRef(StorageDispatcher.props("testStorage", index, chunkIO), "storage")
  val testRegion = TestActorRef(VirtualRegionDispatcher.props("testRegion"), "testRegion")

  "Virtual region" should "register storage" in {
    testRegion ! VirtualRegionDispatcher.Register("testStorage", storage)
    expectNoMsg(100 millis)
  }

  it should "write chunk" in {
    StorageEvent.stream.subscribe(testActor, "testStorage")
    val result = testRegion ? WriteChunk(chunk)
    result.futureValue shouldBe WriteChunk.Success(chunk, chunk)
    expectMsg(StorageEnvelope("testStorage", StorageEvent.ChunkWritten(chunk)))
    val StorageEnvelope("testStorage", StorageEvent.PendingIndexUpdated(diff)) = receiveOne(1 second)
    diff.folders shouldBe empty
    diff.time should be > TestUtils.testTimestamp
    diff.chunks.newChunks shouldBe Set(chunk)
    diff.chunks.deletedChunks shouldBe empty
    expectNoMsg(1 second)
    StorageEvent.stream.unsubscribe(testActor, "testStorage")
  }

  it should "read chunk" in {
    val future = testRegion ? ReadChunk(chunk.withoutData)
    whenReady(future) {
      case ReadChunk.Success(_, source) ⇒
        val probe = source
          .fold(ByteString.empty)(_ ++ _)
          .runWith(TestSink.probe)
        probe.requestNext(chunk.data.encrypted)
        probe.expectComplete()
    }
  }

  it should "deduplicate chunk" in {
    val wrongChunk = chunk.copy(encryption = chunk.encryption.copy(EncryptionMethod.AES()), data = chunk.data.copy(encrypted = randomBytes(chunk.data.plain.length)))
    wrongChunk shouldNot be (chunk)
    val result = testRegion ? WriteChunk(wrongChunk)
    result.futureValue shouldBe WriteChunk.Success(chunk, chunk)
  }

  it should "synchronize index" in {
    StorageEvent.stream.subscribe(testActor, "testStorage")
    index ! IndexSynchronizer.Synchronize
    val StorageEnvelope("testStorage", StorageEvent.IndexUpdated(sequenceNr, diff, remote)) = receiveOne(5 seconds)
    sequenceNr shouldBe 1
    diff.time shouldBe > (TestUtils.testTimestamp)
    diff.folders shouldBe empty
    diff.chunks.newChunks shouldBe Set(chunk)
    diff.chunks.deletedChunks shouldBe empty
    remote shouldBe false
    expectNoMsg(1 seconds)
    StorageEvent.stream.unsubscribe(testActor)
  }
}
