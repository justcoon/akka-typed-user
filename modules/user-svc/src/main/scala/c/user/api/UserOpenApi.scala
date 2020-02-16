package c.user.api

import akka.Done
import akka.actor.{ ActorSystem, CoordinatedShutdown }
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import c.user.UserApp.HttpApiConfig
import c.user.api.openapi.definitions.{ Address, User, UserSearchResponse }
import c.user.api.openapi.user.{ UserHandler, UserResource }
import c.user.domain.UserEntity
import c.user.domain.proto
import c.user.service.{ UserRepository, UserService }
import org.slf4j.LoggerFactory
import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object UserOpenApi {

  private final object BindFailure extends CoordinatedShutdown.Reason

  def server(
      userService: UserService,
      userRepository: UserRepository[Future],
      shutdown: CoordinatedShutdown,
      config: HttpApiConfig
  )(implicit askTimeout: Timeout, ec: ExecutionContext, mat: akka.stream.Materializer, sys: ActorSystem): Unit = {
    val log           = LoggerFactory.getLogger(this.getClass)
    val restApiRoutes = route(userService, userRepository)(config.repositoryTimeout, ec, mat)

    Http(sys)
      .bindAndHandle(restApiRoutes, config.address, config.port)
      .onComplete {
        case Success(binding) =>
          val address = binding.localAddress
          log.info("http endpoint url: http://{}:{}/ - started", address.getHostString, address.getPort)

          shutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "http-graceful-terminate") { () =>
            binding.terminate(10.seconds).map { _ =>
              log.info("http endpoint url: http://{}:{}/ - graceful shutdown completed", address.getHostString, address.getPort)
              Done
            }
          }
        case Failure(ex) =>
          log.error("http endpoint - failed to bind, terminating system", ex)
          shutdown.run(BindFailure)
      }
  }

  def route(
      userService: UserService,
      userRepository: UserRepository[Future]
  )(implicit askTimeout: Timeout, ec: ExecutionContext, mat: akka.stream.Materializer): Route =
    UserResource.routes(handler(userService, userRepository))

  def handler(
      userService: UserService,
      userRepository: UserRepository[Future]
  )(implicit askTimeout: Timeout, ec: ExecutionContext): UserHandler = {
    import io.scalaland.chimney.dsl._
    new UserHandler {
      override def createUser(respond: UserResource.createUserResponse.type)(body: User): Future[UserResource.createUserResponse] = {
        import UserEntity._
        val id  = body.id.getOrElse(body.username).asUserId
        val cmd = body.into[UserEntity.CreateUserCommand].withFieldComputed(_.entityId, _ => id).transform
        userService.sendCommand(cmd).map {
          case reply: UserEntity.UserCreatedReply => UserResource.createUserResponseOK(reply.entityId)
          case reply: UserEntity.UserCreatedFailedReply =>
            UserResource.createUserResponseBadRequest(s"User register error (${reply.error})")
          case _: UserEntity.UserAlreadyExistsReply => UserResource.createUserResponseBadRequest("User already exits")
        }
      }

      override def getUsers(respond: UserResource.getUsersResponse.type)(): Future[UserResource.getUsersResponse] =
        userRepository.findAll().map { r =>
          UserResource.getUsersResponseOK(r.map(_.transformInto[User]).toVector)
        }

      override def getUser(respond: UserResource.getUserResponse.type)(id: String): Future[UserResource.getUserResponse] = {
        import UserEntity._
        userRepository.find(id.asUserId).map {
          case Some(r) =>
            UserResource.getUserResponseOK(r.transformInto[User])
          case _ => UserResource.getUserResponseNotFound
        }
      }

      override def updateUserAddress(
          respond: UserResource.updateUserAddressResponse.type
      )(id: String, body: Address): Future[UserResource.updateUserAddressResponse] = {
        import UserEntity._
        val cmd = UserEntity.ChangeUserAddressCommand(id.asUserId, Some(body.transformInto[proto.Address]))
        userService.sendCommand(cmd).map {
          case reply: UserEntity.UserAddressChangedReply => UserResource.updateUserAddressResponseOK(reply.entityId)
          case reply: UserEntity.UserAddressChangedFailedReply =>
            UserResource.updateUserAddressResponseBadRequest(s"User address update error (${reply.error})")
          case _: UserEntity.UserNotExistsReply => UserResource.updateUserAddressResponseBadRequest("User not exists")
        }
      }

      override def deleteUserAddress(
          respond: UserResource.deleteUserAddressResponse.type
      )(id: String): Future[UserResource.deleteUserAddressResponse] = {
        import UserEntity._
        val cmd = UserEntity.ChangeUserAddressCommand(id.asUserId, None)
        userService.sendCommand(cmd).map {
          case reply: UserEntity.UserAddressChangedReply => UserResource.deleteUserAddressResponseOK(reply.entityId)
          case _: UserEntity.UserNotExistsReply          => UserResource.deleteUserAddressResponseBadRequest("User not exists")
        }
      }

      override def deleteUser(respond: UserResource.deleteUserResponse.type)(id: String): Future[UserResource.deleteUserResponse] =
        Future.successful(UserResource.deleteUserResponseBadRequest) // FIXME

      override def searchUsers(
          respond: UserResource.searchUsersResponse.type
      )(query: Option[String], page: Int, pageSize: Int, sort: Option[Iterable[String]] = None): Future[UserResource.searchUsersResponse] = {
        // sort - field:order (username:asc,email:desc)
        // TODO improve parsing
        val ss = sort.getOrElse(Seq.empty).map { sort =>
          sort.split(":").toList match {
            case p :: o :: Nil if o.toLowerCase == "desc" =>
              (p, false)
            case _ =>
              (sort, true)
          }
        }
        userRepository.search(query, page, pageSize, ss).map { res =>
          val items = res.items.map(_.transformInto[User]).toVector
          UserResource.searchUsersResponseOK(UserSearchResponse(items, res.page, res.pageSize, res.count))
        }
      }
    }
  }
}
