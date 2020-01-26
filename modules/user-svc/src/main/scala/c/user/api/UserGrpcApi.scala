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

      override def updateUserEmail(in: UpdateEmailReq): Future[UpdateEmailRes] = {
        import UserEntity._
        val cmd = UserEntity.ChangeUserEmailCommand(in.id.asUserId, in.email)
        userService.sendCommand(cmd).map {
          case reply: UserEntity.UserEmailChangedReply => UpdateEmailRes(reply.entityId)
          case reply: UserEntity.UserNotExistsReply    => UpdateEmailRes(reply.entityId) // FIXME
        }
      }

      override def updateUserPassword(in: UpdatePasswordReq): Future[UpdatePasswordRes] = {
        import UserEntity._
        val cmd = UserEntity.ChangeUserPasswordCommand(in.id.asUserId, in.pass)
        userService.sendCommand(cmd).map {
          case reply: UserEntity.UserPasswordChangedReply => UpdatePasswordRes(reply.entityId)
          case reply: UserEntity.UserNotExistsReply       => UpdatePasswordRes(reply.entityId) // FIXME
        }
      }

      override def updateUserAddress(in: UpdateAddressReq): Future[UpdateAddressRes] = {
        import UserEntity._
        val cmd = UserEntity.ChangeUserAddressCommand(in.id.asUserId, in.address)
        userService.sendCommand(cmd).map {
          case reply: UserEntity.UserAddressChangedReply => UpdateAddressRes(reply.entityId)
          case reply: UserEntity.UserNotExistsReply      => UpdateAddressRes(reply.entityId) // FIXME
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

      override def searchUsers(in: SearchUsersReq): Future[SearchUsersRes] =
        userRepository.search(Some(in.query), in.page, in.pageSize).map { r =>
          SearchUsersRes(r.items.map(_.transformInto[proto.User]), r.page, r.pageSize, r.count)
        }

    }
  }
}
