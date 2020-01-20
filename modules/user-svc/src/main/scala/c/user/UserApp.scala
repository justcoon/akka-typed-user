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
import c.cqrs.offsetstore.OffsetStoreService
import c.user.api.{ UserGrpcApi, UserRestApi }
import c.user.service.{ UserESRepository, UserESRepositoryInitializer, UserService, UserViewBuilder }
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.akka.{ AkkaHttpClient, AkkaHttpClientSettings }
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

      implicit val askTimeout: Timeout = 3.seconds

      val userService = new UserService()

      val offsetStoreService = new OffsetStoreService()

      val elasticClient = {
        val akkaClient = AkkaHttpClient(
          AkkaHttpClientSettings(Seq(s"${elasticsearchConfig.address}:${elasticsearchConfig.port}"))
        )

        ElasticClient(akkaClient)
      }

      UserESRepositoryInitializer.init(elasticsearchConfig.indexName, elasticClient)

      val userRepository = new UserESRepository(elasticsearchConfig.indexName, elasticClient)

      UserViewBuilder.create(userRepository, offsetStoreService)

      val restApiRoutes = UserRestApi.route(userService, userRepository)(restApiConfig.repositoryTimeout, ec)

      Http(sys)
        .bindAndHandle(restApiRoutes, restApiConfig.address, restApiConfig.port)
        .onComplete {
          case Success(binding) =>
            val address = binding.localAddress
            sys.log.info("http endpoint url: http://{}:{}/ - started", address.getHostString, address.getPort)

            shutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "http-graceful-terminate") { () =>
              binding.terminate(10.seconds).map { _ =>
                sys.log
                  .info("http endpoint url: http://{}:{}/ - graceful shutdown completed", address.getHostString, address.getPort)
                Done
              }
            }
          case Failure(ex) =>
            sys.log.error("http endpoint - failed to bind, terminating system", ex)
            sys.terminate()
        }

      val grpcApiHandler = UserGrpcApi.handler(userService, userRepository)(grpcApiConfig.repositoryTimeout, ec, mat, classicSys)

      Http(sys)
        .bindAndHandleAsync(grpcApiHandler, grpcApiConfig.address, grpcApiConfig.port)
        .onComplete {
          case Success(binding) =>
            val address = binding.localAddress
            sys.log.info("grpc endpoint url: http://{}:{}/ - started", address.getHostString, address.getPort)

            shutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "grpc-graceful-terminate") { () =>
              binding.terminate(10.seconds).map { _ =>
                sys.log
                  .info("grpc endpoint url: http://{}:{}/ - graceful shutdown completed", address.getHostString, address.getPort)
                Done
              }
            }
          case Failure(ex) =>
            sys.log.error("grpc endpoint - failed to bind, terminating system", ex)
            sys.terminate()
        }

      log.info("user up and running")

      Behaviors.empty[Nothing]
    }
  }

  def main(args: Array[String]): Unit =
    ActorSystem[Nothing](RootBehavior(), "user")

  case class ElasticsearchConfig(address: String, port: Int, indexName: String)

  case class HttpApiConfig(address: String, port: Int, repositoryTimeout: FiniteDuration)

}
