package com.jc.user.api

import akka.Done
import akka.actor.{ ActorSystem, CoordinatedShutdown }
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.jc.user.api.openapi.definitions.{ Address, User, UserSearchResponse }
import com.jc.user.api.openapi.user.{ UserHandler, UserResource }
import com.jc.user.config.HttpApiConfig
import com.jc.user.domain.UserEntity
import com.jc.user.domain.proto
import com.jc.user.service.{ UserRepository, UserService }
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
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
    import eu.timepit.refined.auto._

    val log           = LoggerFactory.getLogger(this.getClass)
    val restApiRoutes = route(userService, userRepository)(config.repositoryTimeout, ec, mat)

    Http(sys)
      .newServerAt(config.address, config.port)
      .bind(restApiRoutes)
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
      override def createUser(respond: UserResource.CreateUserResponse.type)(body: User): Future[UserResource.CreateUserResponse] = {
        import UserEntity._
        val id  = body.id.getOrElse(body.username).asUserId
        val cmd = body.into[UserEntity.CreateUserCommand].withFieldComputed(_.entityId, _ => id).transform
        userService.sendCommand(cmd).map {
          case reply: UserEntity.UserCreatedReply => UserResource.CreateUserResponseOK(reply.entityId)
          case reply: UserEntity.UserCreatedFailedReply =>
            UserResource.CreateUserResponseBadRequest(s"User register error (${reply.error})")
          case _: UserEntity.UserAlreadyExistsReply => UserResource.CreateUserResponseBadRequest("User already exits")
        }
      }

      override def getUsers(respond: UserResource.GetUsersResponse.type)(): Future[UserResource.GetUsersResponse] =
        userRepository.findAll().map { r =>
          UserResource.GetUsersResponseOK(r.map(_.transformInto[User]).toVector)
        }

      override def getUser(respond: UserResource.GetUserResponse.type)(id: String): Future[UserResource.GetUserResponse] = {
        import UserEntity._
        userRepository.find(id.asUserId).map {
          case Some(r) =>
            UserResource.GetUserResponseOK(r.transformInto[User])
          case _ => UserResource.GetUserResponseNotFound
        }
      }

      override def updateUserAddress(
          respond: UserResource.UpdateUserAddressResponse.type
      )(id: String, body: Address): Future[UserResource.UpdateUserAddressResponse] = {
        import UserEntity._
        val cmd = UserEntity.ChangeUserAddressCommand(id.asUserId, Some(body.transformInto[proto.Address]))
        userService.sendCommand(cmd).map {
          case reply: UserEntity.UserAddressChangedReply => UserResource.UpdateUserAddressResponseOK(reply.entityId)
          case reply: UserEntity.UserAddressChangedFailedReply =>
            UserResource.UpdateUserAddressResponseBadRequest(s"User address update error (${reply.error})")
          case _: UserEntity.UserNotExistsReply => UserResource.UpdateUserAddressResponseBadRequest("User not exists")
        }
      }

      override def deleteUserAddress(
          respond: UserResource.DeleteUserAddressResponse.type
      )(id: String): Future[UserResource.DeleteUserAddressResponse] = {
        import UserEntity._
        val cmd = UserEntity.ChangeUserAddressCommand(id.asUserId, None)
        userService.sendCommand(cmd).map {
          case reply: UserEntity.UserAddressChangedReply => UserResource.DeleteUserAddressResponseOK(reply.entityId)
          case reply: UserEntity.UserAddressChangedFailedReply =>
            UserResource.DeleteUserAddressResponseBadRequest(s"User address update error (${reply.error})")
          case _: UserEntity.UserNotExistsReply => UserResource.DeleteUserAddressResponseBadRequest("User not exists")
        }
      }

      override def deleteUser(respond: UserResource.DeleteUserResponse.type)(id: String): Future[UserResource.DeleteUserResponse] =
        Future.successful(UserResource.DeleteUserResponseBadRequest) // FIXME

      override def searchUsers(
          respond: UserResource.SearchUsersResponse.type
      )(
          query: Option[String],
          page: Int,
          pageSize: Int,
          sort: Option[Iterable[String]] = None
      ): Future[UserResource.SearchUsersResponse] = {
        // sort - field:order (username:asc,email:desc)
        val ss = sort.getOrElse(Seq.empty).map(toFieldSort)

        userRepository.search(query, page, pageSize, ss).map {
          case Right(res) =>
            val items = res.items.map(_.transformInto[User]).toVector
            UserResource.SearchUsersResponseOK(UserSearchResponse(items, res.page, res.pageSize, res.count))
          case Left(e) =>
            UserResource.SearchUsersResponseBadRequest(e.error)
        }
      }
    }
  }

  // TODO improve parsing
  // sort - field:order, examples: username:asc, email:desc
  def toFieldSort(sort: String): UserRepository.FieldSort =
    sort.split(":").toList match {
      case p :: o :: Nil if o.toLowerCase == "desc" =>
        (p, false)
      case _ =>
        (sort, true)
    }
}
