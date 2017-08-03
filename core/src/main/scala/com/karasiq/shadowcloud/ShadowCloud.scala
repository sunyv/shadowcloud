package com.karasiq.shadowcloud

import java.util.UUID

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor.{ActorContext, ActorRef, ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import com.typesafe.config.Config

import com.karasiq.shadowcloud.actors.RegionSupervisor
import com.karasiq.shadowcloud.actors.messages.{RegionEnvelope, StorageEnvelope}
import com.karasiq.shadowcloud.actors.utils.StringEventBus
import com.karasiq.shadowcloud.config.{RegionConfig, SCConfig, StorageConfig}
import com.karasiq.shadowcloud.config.keys.{KeyChain, KeyProvider, KeySet}
import com.karasiq.shadowcloud.config.passwords.PasswordProvider
import com.karasiq.shadowcloud.config.utils.ConfigImplicits
import com.karasiq.shadowcloud.providers.SCModules
import com.karasiq.shadowcloud.serialization.{SerializationModule, SerializationModules}
import com.karasiq.shadowcloud.storage.props.StorageProps
import com.karasiq.shadowcloud.streams._
import com.karasiq.shadowcloud.utils.{ProviderInstantiator, SCProviderInstantiator}

object ShadowCloud extends ExtensionId[ShadowCloudExtension] with ExtensionIdProvider {
  def apply()(implicit context: ActorContext): ShadowCloudExtension = {
    apply(context.system)
  }

  def createExtension(system: ExtendedActorSystem): ShadowCloudExtension = {
    new ShadowCloudExtension(system)
  }

  def lookup(): ExtensionId[_ <: Extension] = {
    ShadowCloud
  }
}

class ShadowCloudExtension(_actorSystem: ExtendedActorSystem) extends Extension {
  // -----------------------------------------------------------------------
  // Context
  // -----------------------------------------------------------------------
  object implicits {
    implicit val actorSystem: ActorSystem = _actorSystem
    implicit val executionContext: ExecutionContext = _actorSystem.dispatcher
    implicit val materializer: Materializer = ActorMaterializer()(_actorSystem)
    implicit val defaultTimeout: Timeout = Timeout(5 seconds)
    private[ShadowCloudExtension] implicit val pInst: ProviderInstantiator = new SCProviderInstantiator(ShadowCloudExtension.this)
  }

  import implicits._

  // -----------------------------------------------------------------------
  // Configuration
  // -----------------------------------------------------------------------

  private[this] val rootConfig: Config = actorSystem.settings.config.getConfig("shadowcloud")
  val config: SCConfig = SCConfig(rootConfig)
  val modules: SCModules = SCModules(config)
  val serialization: SerializationModule = SerializationModules.forActorSystem(actorSystem)

  object configs {
    def regionConfig(regionId: String): RegionConfig = {
      RegionConfig.forId(regionId, rootConfig)
    }

    def storageConfig(storageId: String): StorageConfig = { // Uses only static config
      StorageConfig.forId(storageId, rootConfig)
    }

    def storageConfig(storageId: String, storageProps: StorageProps): StorageConfig = {
      StorageConfig.forProps(storageId, storageProps, rootConfig)
    }
  }

  object keys {
    val provider: KeyProvider = pInst.getInstance(config.crypto.keyProvider)

    def generateKeySet(): KeySet = {
      val enc = modules.crypto.encryptionModule(config.crypto.encryption.keys).createParameters()
      val sign = modules.crypto.signModule(config.crypto.signing.index).createParameters()
      KeySet(UUID.randomUUID(), sign, enc)
    }

    def getOrGenerateChain(): Future[KeyChain] = {
      provider.getKeyChain().flatMap { chain ⇒
        if (chain.encKeys.isEmpty) {
          val keySet = generateKeySet()
          provider
            .addKeySet(keySet)
            .flatMap(_ ⇒ provider.getKeyChain())
            .filter(_.encKeys.contains(keySet))
        } else {
          Future.successful(chain)
        }
      }
    }
  }

  object passwords extends ConfigImplicits {
    val provider: PasswordProvider = pInst.getInstance(config.crypto.passwordProvider)

    def getOrAsk(configPath: String, passwordId: String): String = {
      rootConfig.withDefault(provider.askPassword(passwordId), _.getString(configPath))
    }
  }

  // -----------------------------------------------------------------------
  // Actors
  // -----------------------------------------------------------------------
  object actors {
    val regionSupervisor: ActorRef = _actorSystem.actorOf(RegionSupervisor.props, "shadowcloud")
  }

  // -----------------------------------------------------------------------
  // Events
  // -----------------------------------------------------------------------
  object eventStreams { // TODO: Supervisor events
    val region = new StringEventBus[RegionEnvelope](_.regionId)
    val storage = new StringEventBus[StorageEnvelope](_.storageId)

    def publishRegionEvent(regionId: String, event: Any): Unit = {
      region.publish(RegionEnvelope(regionId, event))
    }

    def publishStorageEvent(storageId: String, event: Any): Unit = {
      storage.publish(StorageEnvelope(storageId, event))
    }
  }

  // -----------------------------------------------------------------------
  // Streams
  // -----------------------------------------------------------------------
  object streams {
    val chunk = ChunkProcessingStreams(config)
    val index = IndexProcessingStreams(ShadowCloudExtension.this)
    val region = RegionStreams(actors.regionSupervisor, config.parallelism, config.timeouts)
    val file = FileStreams(region, chunk)
    val metadata = MetadataStreams(config.metadata, modules.metadata, file, ops.region, serialization)
  }

  object ops {
    val supervisor = RegionSupervisorOps(actors.regionSupervisor, config.timeouts)
    val region = RegionOps(actors.regionSupervisor, config.timeouts)
    val storage = StorageOps(actors.regionSupervisor, config.timeouts)
    val background = BackgroundOps(config, this.region)
  }
}
