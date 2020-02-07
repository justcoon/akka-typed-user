package c.user.api
import akka.Done
import akka.actor.{ ActorSystem, CoordinatedShutdown }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.Materializer
import akka.util.Timeout
import c.user.UserApp.HttpApiConfig
import c.user.api.proto._
import c.user.domain.UserEntity
import c.user.domain.proto
import c.user.service.{ UserRepository, UserService }
import org.slf4j.LoggerFactory

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object UserGrpcApi {

  private final object BindFailure extends CoordinatedShutdown.Reason

  def server(
      userService: UserService,
      userRepository: UserRepository[Future],
      shutdown: CoordinatedShutdown,
      config: HttpApiConfig
  )(implicit askTimeout: Timeout, ec: ExecutionContext, mat: akka.stream.Materializer, sys: ActorSystem): Unit = {
    val log            = LoggerFactory.getLogger(this.getClass)
    val grpcApiHandler = handler(userService, userRepository)(config.repositoryTimeout, ec, mat, sys)

    Http(sys)
      .bindAndHandleAsync(grpcApiHandler, config.address, config.port)
      .onComplete {
        case Success(binding) =>
          val address = binding.localAddress
          log.info("grpc endpoint url: http://{}:{}/ - started", address.getHostString, address.getPort)

          shutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "grpc-graceful-terminate") { () =>
            binding.terminate(10.seconds).map { _ =>
              log
                .info("grpc endpoint url: http://{}:{}/ - graceful shutdown completed", address.getHostString, address.getPort)
              Done
            }
          }
        case Failure(ex) =>
          log.error("grpc endpoint - failed to bind, terminating system", ex)
          shutdown.run(BindFailure)
      }
  }

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
          case reply: UserEntity.UserCreatedReply =>
            RegisterUserRes(reply.entityId, RegisterUserRes.Result.Success("User registered"))
          case reply: UserEntity.UserAlreadyExistsReply =>
            RegisterUserRes(reply.entityId, RegisterUserRes.Result.Failure("User already exits"))
          case reply: UserEntity.UserCreatedFailedReply =>
            RegisterUserRes(reply.entityId, RegisterUserRes.Result.Failure(s"User register error (${reply.error})"))
        }
      }

      override def updateUserEmail(in: UpdateEmailReq): Future[UpdateEmailRes] = {
        import UserEntity._
        val cmd = UserEntity.ChangeUserEmailCommand(in.id.asUserId, in.email)
        userService.sendCommand(cmd).map {
          case reply: UserEntity.UserEmailChangedReply =>
            UpdateEmailRes(reply.entityId, UpdateEmailRes.Result.Success("User email updated"))
          case reply: UserEntity.UserNotExistsReply =>
            UpdateEmailRes(reply.entityId, UpdateEmailRes.Result.Failure("User not exits"))
        }
      }

      override def updateUserPassword(in: UpdatePasswordReq): Future[UpdatePasswordRes] = {
        import UserEntity._
        val cmd = UserEntity.ChangeUserPasswordCommand(in.id.asUserId, in.pass)
        userService.sendCommand(cmd).map {
          case reply: UserEntity.UserPasswordChangedReply =>
            UpdatePasswordRes(reply.entityId, UpdatePasswordRes.Result.Success("User password updated"))
          case reply: UserEntity.UserNotExistsReply =>
            UpdatePasswordRes(reply.entityId, UpdatePasswordRes.Result.Failure("User not exists"))
        }
      }

      override def updateUserAddress(in: UpdateAddressReq): Future[UpdateAddressRes] = {
        import UserEntity._
        val cmd = UserEntity.ChangeUserAddressCommand(in.id.asUserId, in.address)
        userService.sendCommand(cmd).map {
          case reply: UserEntity.UserAddressChangedReply =>
            UpdateAddressRes(reply.entityId, UpdateAddressRes.Result.Success("User address updated"))
          case reply: UserEntity.UserNotExistsReply =>
            UpdateAddressRes(reply.entityId, UpdateAddressRes.Result.Failure("User not exists"))
          case reply: UserEntity.UserAddressChangedFailedReply =>
            UpdateAddressRes(reply.entityId, UpdateAddressRes.Result.Failure(s"User address update error (${reply.error})"))
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
