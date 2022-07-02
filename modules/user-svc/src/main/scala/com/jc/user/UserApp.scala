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
import com.jc.cqrs.offsetstore.CassandraProjectionOffsetStore
import com.jc.logging.{ ClusterSetLoggingSystem, LogbackLoggingSystem }
import com.jc.user.api.{ UserGrpcApi, UserOpenApi }
import com.jc.user.domain.{ DepartmentService, SimpleAddressValidationService, UserService }
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
      import com.jc.refined.auto._
      import com.jc.user.config._

      val log = ctx.log

      implicit val sys        = ctx.system
      implicit val classicSys = sys.toClassic
      implicit val sharding   = ClusterSharding(sys)
      implicit val ec         = ctx.executionContext
      implicit val mat        = Materializer(ctx)
      implicit val shutdown   = CoordinatedShutdown(classicSys)

      val appConfig = ConfigSource.fromConfig(sys.settings.config).loadOrThrow[AppConfig]

      val loggingSystem = ClusterSetLoggingSystem(LogbackLoggingSystem())(ctx, mat)
      // log.info(sys.settings.toString)

      log.info("kamon - init")
      Kamon.init()

      // akka discovery

      val listener = ctx.spawn(
        Behaviors.receive[ClusterEvent.MemberEvent] { (ctx, event) =>
          ctx.log.info("cluster member event: {}", event)
          Behaviors.same
        },
        "listener"
      )

      Cluster(sys).subscriptions ! Subscribe(listener, classOf[ClusterEvent.MemberEvent])

      AkkaManagement.get(classicSys).start()

      ClusterBootstrap.get(classicSys).start()

      implicit val askTimeout: Timeout = 3.seconds

      val jwtAuthenticator = PdiJwtAuthenticator.create(appConfig.jwt, Clock.systemUTC())

      val addressValidationService = SimpleAddressValidationService
      val departmentService        = DepartmentService()
      val userService              = UserService(departmentService, addressValidationService)

      log.info("offset store - init")
      CassandraProjectionOffsetStore.init()

      val elasticClient = {
        val settings   = AkkaHttpClientSettings(appConfig.elasticsearch.addresses)
        val akkaClient = AkkaHttpClient(settings)
        ElasticClient(akkaClient)
      }

      log.info("repo - init")

      RepositoryInitializer.init(
        DepartmentESRepositoryInitializer(appConfig.elasticsearch.departmentIndexName, elasticClient),
        UserESRepositoryInitializer(appConfig.elasticsearch.userIndexName, elasticClient)
      )

      val departmentRepository = DepartmentESRepository(appConfig.elasticsearch.departmentIndexName, elasticClient)

      val userRepository = UserESRepository(appConfig.elasticsearch.userIndexName, elasticClient)

      log.info("view builder - create")
      DepartmentViewBuilder.create(departmentRepository)
      UserViewBuilder.create(userRepository)

      log.info("kafka producer - create")
      DepartmentKafkaProducer.create(appConfig.kafka.departmentTopic)
      UserKafkaProducer.create(appConfig.kafka.userTopic)

      log.info("rest api server - create")
      UserOpenApi
        .server(userService, userRepository, departmentService, departmentRepository, loggingSystem, jwtAuthenticator, appConfig.restApi)

      log.info("grpc api server - create")
      UserGrpcApi
        .server(
          userService,
          userRepository,
          departmentService,
          departmentRepository,
          loggingSystem,
          jwtAuthenticator,
          appConfig.grpcApi
        )

      log.info("service up and running")

      Behaviors.empty[Nothing]
    }
  }

  def main(args: Array[String]): Unit =
    ActorSystem[Nothing](RootBehavior(), "user")

}
