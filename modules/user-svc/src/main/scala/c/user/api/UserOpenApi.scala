package c.user.api

import akka.http.scaladsl.server.Route
import akka.util.Timeout
import c.user.api.openapi.definitions.User
import c.user.api.openapi.user.{ UserHandler, UserResource }
import c.user.domain.UserEntity
import c.user.service.{ UserRepository, UserService }

import scala.concurrent.{ ExecutionContext, Future }

object UserOpenApi {

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
          case reply: UserEntity.UserCreatedReply   => UserResource.createUserResponseOK(reply.entityId)
          case _: UserEntity.UserAlreadyExistsReply => UserResource.createUserResponseBadRequest
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

      override def updateUser(
          respond: UserResource.updateUserResponse.type
      )(id: String, body: User): Future[UserResource.updateUserResponse] =
        Future.successful(UserResource.updateUserResponseBadRequest) // FIXME

      override def deleteUser(respond: UserResource.deleteUserResponse.type)(id: String): Future[UserResource.deleteUserResponse] =
        Future.successful(UserResource.deleteUserResponseBadRequest) // FIXME
    }
  }
}
