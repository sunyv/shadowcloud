package com.karasiq.shadowcloud.test.config

import scala.concurrent.duration._
import scala.language.postfixOps

import org.scalatest.{FlatSpec, Matchers}

import com.karasiq.shadowcloud.config.{RegionConfig, SCConfig, StorageConfig}
import com.karasiq.shadowcloud.crypto.HashingMethod
import com.karasiq.shadowcloud.test.utils.TestUtils

class SCConfigTest extends FlatSpec with Matchers {
  "Application config" should "be loaded" in {
    val config = SCConfig(TestUtils.rootConfig)
    config.crypto.hashing.chunks shouldBe HashingMethod("Blake2b")
    config.crypto.hashing.files shouldBe HashingMethod.none
  }

  "Region-specific config" should "be loaded" in {
    val regionConfig = RegionConfig.forId("testRegion", TestUtils.rootConfig)
    regionConfig.dataReplicationFactor shouldBe 0
    regionConfig.indexReplicationFactor shouldBe 3
  }

  "Storage-specific config" should "be loaded" in {
    val storageConfig = StorageConfig.forId("testStorage", TestUtils.rootConfig)
    storageConfig.indexCompactThreshold shouldBe 1234
    storageConfig.syncInterval shouldBe 111.seconds
  }
}