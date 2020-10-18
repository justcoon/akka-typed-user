package com.jc.user

import java.time.Clock

import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ ActorSystem, Behavior }
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.ClusterEvent
import akka.cluster.typed.{ Cluster, Subscribe }
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.stream.Materializer
import akka.util.Timeout
import com.jc.auth.PdiJwtAuthenticator
import com.jc.cqrs.offsetstore.{ CassandraOffsetStore, CassandraOffsetStoreService, CassandraProjectionOffsetStore }
import com.jc.user.api.{ UserGrpcApi, UserOpenApi }
import com.jc.user.service._
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.akka.{ AkkaHttpClient, AkkaHttpClientSettings }
import kamon.Kamon
import pureconfig._

import scala.concurrent.duration._

object UserApp {

  private object RootBehavior {

    def apply(): Behavior[Nothing] = Behaviors.setup[Nothing] { ctx =>
      import eu.timepit.refined.auto._
      import config._

      val log = ctx.log

      implicit val sys        = ctx.system
      implicit val classicSys = sys.toClassic
      implicit val sharding   = ClusterSharding(sys)
      implicit val ec         = ctx.executionContext
      implicit val mat        = Materializer(ctx)
      val shutdown            = CoordinatedShutdown(classicSys)

      val appConfig = ConfigSource.fromConfig(sys.settings.config).loadOrThrow[AppConfig]

      log.info("kamon - init")
      Kamon.init()

      // akka discovery

      val listener = ctx.spawn(Behaviors.receive[ClusterEvent.MemberEvent] { (ctx, event) =>
        ctx.log.info("user cluster member event: {}", event)
        Behaviors.same
      }, "listener")

      Cluster(sys).subscriptions ! Subscribe(listener, classOf[ClusterEvent.MemberEvent])

      AkkaManagement.get(classicSys).start()

      ClusterBootstrap.get(classicSys).start()

      implicit val askTimeout: Timeout = 3.seconds

      val jwtAuthenticator = PdiJwtAuthenticator.create(appConfig.jwt, Clock.systemUTC())

      val userService = new UserService()

//      val journalKeyspace = sys.settings.config.getString(CassandraOffsetStore.JournalKeyspaceConfigPath)

      log.info("offset store - init")
//      CassandraOffsetStore.init(journalKeyspace)
//      val offsetStoreService = new CassandraOffsetStoreService(journalKeyspace)
      CassandraProjectionOffsetStore.init()

      val elasticClient = {
        val settings   = AkkaHttpClientSettings(appConfig.elasticsearch.addresses)
        val akkaClient = AkkaHttpClient(settings)
        ElasticClient(akkaClient)
      }

      log.info("user repo - init")
      UserESRepositoryInitializer.init(appConfig.elasticsearch.indexName, elasticClient)

      val userRepository = new UserESRepository(appConfig.elasticsearch.indexName, elasticClient)

      log.info("user view builder - create")
//      UserViewBuilder.create(userRepository, offsetStoreService)
      UserViewBuilder.createWithProjection(userRepository)

      log.info("user kafka producer - create")
//      UserKafkaProducer.create(appConfig.kafka.topic, appConfig.kafka.addresses, offsetStoreService)
      UserKafkaProducer.createWithProjection(appConfig.kafka.topic, appConfig.kafka.addresses)

      log.info("user rest api server - create")
      UserOpenApi.server(userService, userRepository, jwtAuthenticator, shutdown, appConfig.restApi)(
        appConfig.restApi.repositoryTimeout,
        ec,
        mat,
        classicSys
      )

      log.info("user grpc api server - create")
      UserGrpcApi.server(userService, userRepository, jwtAuthenticator, shutdown, appConfig.grpcApi)(
        appConfig.grpcApi.repositoryTimeout,
        ec,
        classicSys
      )

      log.info("user up and running")

      Behaviors.empty[Nothing]
    }
  }

  def main(args: Array[String]): Unit =
    ActorSystem[Nothing](RootBehavior(), "user")

}
