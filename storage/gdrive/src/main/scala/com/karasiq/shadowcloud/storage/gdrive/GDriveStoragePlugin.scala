package com.karasiq.shadowcloud.storage.gdrive

import akka.actor.{Actor, ActorContext, ActorRef, Props}

import com.karasiq.common.configs.ConfigImplicits._
import com.karasiq.gdrive.context.GDriveContext
import com.karasiq.gdrive.files.GDriveService
import com.karasiq.gdrive.oauth.GDriveOAuth
import com.karasiq.shadowcloud.ShadowCloudExtension
import com.karasiq.shadowcloud.model.StorageId
import com.karasiq.shadowcloud.storage.StoragePlugin
import com.karasiq.shadowcloud.storage.props.StorageProps
import com.karasiq.shadowcloud.storage.utils.StoragePluginBuilder

private[gdrive] object GDriveStoragePlugin {
  def apply(implicit sc: ShadowCloudExtension): GDriveStoragePlugin = {
    new GDriveStoragePlugin()
  }
}

private[gdrive] class GDriveStoragePlugin(implicit sc: ShadowCloudExtension) extends StoragePlugin {
  private[this] def defaultConfig = sc.config.rootConfig.getConfigIfExists("storage.gdrive")

  def createStorage(storageId: StorageId, props: StorageProps)(implicit context: ActorContext) = {
    val config = sc.configs.storageConfig(storageId, props).rootConfig
      .getConfigIfExists("gdrive")
      .withFallback(defaultConfig)

    val proxyProps = Props(new Actor {
      import context.{dispatcher ⇒ executionContext} // API dispatcher

      def receiveAuthorized(storageDispatcher: ActorRef): Receive = {
        case message if sender() == storageDispatcher ⇒
          context.parent ! message

        case message ⇒ 
          storageDispatcher.forward(message)
      }

      def receive = {
        case message ⇒
          implicit val driveContext = {
            val dataStore = SCGDriveStore(storageId, props.credentials.login)
            GDriveContext(config, dataStore)
          }

          val oauth = GDriveOAuth()
          implicit val session = oauth.authorize(props.credentials.login)

          val service = {
            val applicationName = config.withDefault("shadowcloud", _.getString("application-name"))
            GDriveService(applicationName)
          }

          val dispatcher = StoragePluginBuilder(storageId, props)
            .withIndexTree(GDriveRepository(service))
            .withChunksTree(GDriveRepository(service))
            .withHealth(GDriveHealthProvider(service))
            .createStorage()

          context.become(receiveAuthorized(dispatcher))
          self.forward(message)
      }
    })

    context.actorOf(proxyProps.withDispatcher(GDriveDispatchers.apiDispatcherId), "gdrive-proxy")
  }
}
