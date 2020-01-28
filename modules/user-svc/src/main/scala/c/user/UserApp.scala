package c.user

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.{ ActorSystem, Behavior }
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.RouteResult._
import akka.stream.Materializer
import akka.util.Timeout
import c.auth.jwt.JwtConfig
import c.cqrs.offsetstore.OffsetStoreService
import c.user.api.{ UserGrpcApi, UserOpenApi }
import c.user.service.{ UserESRepository, UserESRepositoryInitializer, UserKafkaProducer, UserService, UserViewBuilder }
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.akka.{ AkkaHttpClient, AkkaHttpClientSettings }
import kamon.Kamon
import pureconfig._
import pureconfig.generic.auto._

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Success }

object UserApp {

  private object RootBehavior {

    def apply(): Behavior[Nothing] = Behaviors.setup[Nothing] { ctx =>
      val log = ctx.log

      implicit val sys        = ctx.system
      implicit val classicSys = sys.toClassic
      implicit val sharding   = ClusterSharding(sys)
      implicit val ec         = ctx.executionContext
      implicit val mat        = Materializer(ctx)
      val shutdown            = CoordinatedShutdown(classicSys)

      val config = sys.settings.config

      val restApiConfig =
        ConfigSource.fromConfig(config).at("rest-api").loadOrThrow[HttpApiConfig]

      val grpcApiConfig =
        ConfigSource.fromConfig(config).at("grpc-api").loadOrThrow[HttpApiConfig]

      val elasticsearchConfig =
        ConfigSource.fromConfig(config).at("elasticsearch").loadOrThrow[ElasticsearchConfig]

      val kafkaConfig =
        ConfigSource.fromConfig(config).at("kafka").loadOrThrow[KafkaConfig]

//      val jwtConfig =
//        ConfigSource.fromConfig(config).at("jwt").loadOrThrow[JwtConfig]

      sys.log.info("Kamon init")
      Kamon.init()

      implicit val askTimeout: Timeout = 3.seconds

      val userService = new UserService()

      val offsetStoreService = new OffsetStoreService()

      val elasticClient = {
        val akkaClient = AkkaHttpClient(
          AkkaHttpClientSettings(elasticsearchConfig.addresses)
        )

        ElasticClient(akkaClient)
      }

      UserESRepositoryInitializer.init(elasticsearchConfig.indexName, elasticClient)

      val userRepository = new UserESRepository(elasticsearchConfig.indexName, elasticClient)

      UserViewBuilder.create(userRepository, offsetStoreService)

      UserKafkaProducer.create(kafkaConfig.topic, kafkaConfig.addresses, offsetStoreService)

      UserOpenApi.server(userService, userRepository, shutdown, restApiConfig)(restApiConfig.repositoryTimeout, ec, mat, classicSys)

      UserGrpcApi.server(userService, userRepository, shutdown, grpcApiConfig)(grpcApiConfig.repositoryTimeout, ec, mat, classicSys)

      log.info("user up and running")

      Behaviors.empty[Nothing]
    }
  }

  def main(args: Array[String]): Unit =
    ActorSystem[Nothing](RootBehavior(), "user")

  case class HttpApiConfig(address: String, port: Int, repositoryTimeout: FiniteDuration)

  case class ElasticsearchConfig(addresses: List[String], indexName: String)

  case class KafkaConfig(addresses: List[String], topic: String)
}
