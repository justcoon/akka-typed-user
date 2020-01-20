package c.user.api

import akka.http.scaladsl.model.StatusCodes.{ BadRequest, Created, NotFound, OK }
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ Directives, Route }
import akka.util.Timeout
import c.user.domain.UserEntity
import c.user.service.{ UserRepository, UserService }
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport

import scala.concurrent.{ ExecutionContext, Future }

object UserRestApi {

  final case class Address(
      street: String,
      number: String,
      zip: String,
      city: String,
      state: String,
      country: String
  )

  final case class RegisterUser(
      username: String,
      email: String,
      pass: String,
      address: Option[Address] = None
  )

  final case class GetUser(id: UserEntity.UserId)

  final case class ChangeUserPassword(id: UserEntity.UserId, pass: String)

  final case class ChangeUserEmail(id: UserEntity.UserId, email: String)

  final case class DeleteUser(id: UserEntity.UserId)

  final case class ChangeUserAddress(id: UserEntity.UserId, address: Option[Address])

  def route(
      userService: UserService,
      userRepository: UserRepository[Future]
  )(implicit askTimeout: Timeout, ec: ExecutionContext): Route = {

    import Directives._
    import FailFastCirceSupport._
    import io.circe.generic.auto._
    import io.scalaland.chimney.dsl._

    def users =
      pathPrefix("users") {
        pathEnd {
          get {
            complete(
              userRepository.findAll
            )
          }
        } ~
        path(Segment) { id =>
          get {
            import UserEntity._
            onSuccess(userRepository.find(id.asUserId)) {
              case Some(user) => complete(OK, user)
              case None       => complete(NotFound, s"User with id: $id not found!")
            }
          }
        }
      }

    def user =
      pathPrefix("user") {
        pathEnd {
          post {
            entity(as[RegisterUser]) { addUser =>
              extractUri { uri =>
                def location(id: UserEntity.UserId) = Location(uri.withPath(uri.path / id.toString))

                import UserEntity._
                val cmd = addUser.into[UserEntity.CreateUserCommand].withFieldComputed(_.entityId, u => u.username.asUserId).transform

                onSuccess(userService.sendCommand(cmd)) {
                  case reply: UserEntity.UserCreatedReply       => complete(Created, List(location(reply.entityId)), reply.entityId)
                  case reply: UserEntity.UserAlreadyExistsReply => complete(BadRequest, s"User with id: ${reply.entityId} already exists!")
                }
              }
            }
          }
        } ~
        path("email") {
          post {
            entity(as[ChangeUserEmail]) { changeUserEmail =>
              extractUri { uri =>
                def location(id: UserEntity.UserId) = Location(uri.withPath(uri.path / id.toString / "email"))

                val cmd = changeUserEmail.into[UserEntity.ChangeUserEmailCommand].withFieldComputed(_.entityId, u => u.id).transform

                onSuccess(userService.sendCommand(cmd)) {
                  case reply: UserEntity.UserEmailChangedReply => complete(OK, List(location(reply.entityId)), reply.entityId)
                  case reply: UserEntity.UserNotExistsReply    => complete(BadRequest, s"User with id: ${reply.entityId} not exists!")
                }
              }
            }
          }
        } ~
        path("pass") {
          post {
            entity(as[ChangeUserPassword]) { changeUserPassword =>
              extractUri { uri =>
                def location(id: UserEntity.UserId) = Location(uri.withPath(uri.path / id.toString / "pass"))

                val cmd = changeUserPassword.into[UserEntity.ChangeUserPasswordCommand].withFieldComputed(_.entityId, u => u.id).transform

                onSuccess(userService.sendCommand(cmd)) {
                  case reply: UserEntity.UserPasswordChangedReply => complete(OK, List(location(reply.entityId)), reply.entityId)
                  case reply: UserEntity.UserNotExistsReply       => complete(BadRequest, s"User with id: ${reply.entityId} not exists!")
                }
              }
            }
          }
        } ~
        path(Segment) { id =>
          get {
            import UserEntity._
            onSuccess(userService.sendCommand(UserEntity.GetUserCommand(id.asUserId))) {
              case reply: UserEntity.UserReply => complete(OK, reply.user)

              case _ => complete(NotFound, s"User with id: $id not found!")
            }
          }
        }
//        ~
        //        path(Segment) { id =>
        //          delete {
        //            onSuccess(userAggregateManager ? DeleteUser(id.asUserId)) {
        //              case Removed => complete(NoContent)
        //              //                case IdUnknown(_) => complete(NotFound, s"User with id $id not found!")
        //            }
        //          }
        //        }

      }

    users ~ user
  }
}
