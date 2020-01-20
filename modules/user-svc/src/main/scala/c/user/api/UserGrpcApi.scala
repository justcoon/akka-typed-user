package c.user.api
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.Materializer
import akka.util.Timeout
import c.user.api.proto._
import c.user.domain.UserEntity
import c.user.domain.proto
import c.user.service.{ UserRepository, UserService }

import scala.concurrent.{ ExecutionContext, Future }

object UserGrpcApi {

  def handler(
      userService: UserService,
      userRepository: UserRepository[Future]
  )(
      implicit askTimeout: Timeout,
      ec: ExecutionContext,
      mat: Materializer,
      sys: ActorSystem
  ): HttpRequest => Future[HttpResponse] =
    UserApiServiceHandler(service(userService, userRepository))

  def service(
      userService: UserService,
      userRepository: UserRepository[Future]
  )(implicit askTimeout: Timeout, ec: ExecutionContext): UserApiService = {

    import io.scalaland.chimney.dsl._

    new UserApiService {
      override def registerUser(in: RegisterUserReq): Future[RegisterUserRes] = {

        import UserEntity._
        val id  = in.username.asUserId
        val cmd = in.into[UserEntity.CreateUserCommand].withFieldComputed(_.entityId, _ => id).transform

        userService.sendCommand(cmd).map {
          case reply: UserEntity.UserCreatedReply       => RegisterUserRes(reply.entityId)
          case reply: UserEntity.UserAlreadyExistsReply => RegisterUserRes(reply.entityId) // FIXME
        }
      }

      override def getUser(in: GetUserReq): Future[GetUserRes] = {
        import UserEntity._
        userRepository.find(in.id.asUserId).map { r =>
          GetUserRes(r.map(_.transformInto[proto.User]))
        }
      }

      override def getUsers(in: GetUsersReq): Future[GetUsersRes] =
        userRepository.findAll().map { r =>
          GetUsersRes(r.map(_.transformInto[proto.User]))
        }
    }
  }
}
