package com.jc.user.api

import akka.Done
import akka.actor.{ ActorSystem, CoordinatedShutdown }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.Materializer
import akka.util.Timeout
import com.jc.user.api.proto._
import com.jc.user.config.HttpApiConfig
import com.jc.user.domain.UserEntity
import com.jc.user.domain.proto
import com.jc.user.service.{ UserRepository, UserService }
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object UserGrpcApi {

  private final object BindFailure extends CoordinatedShutdown.Reason

  def server(
      userService: UserService,
      userRepository: UserRepository[Future],
      shutdown: CoordinatedShutdown,
      config: HttpApiConfig
  )(implicit askTimeout: Timeout, ec: ExecutionContext, sys: ActorSystem): Unit = {
    import eu.timepit.refined.auto._

    val log            = LoggerFactory.getLogger(this.getClass)
    val grpcApiHandler = handler(userService, userRepository)(config.repositoryTimeout, ec, sys)

    Http(sys)
      .newServerAt(config.address, config.port).bind(grpcApiHandler)
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

      override def searchUsers(in: SearchUsersReq): Future[SearchUsersRes] = {
        val ss = in.sorts.map { sort =>
          (sort.field, sort.order.isAsc)
        }
        userRepository.search(Some(in.query), in.page, in.pageSize, ss).map {
          case Right(r) =>
            SearchUsersRes(r.items.map(_.transformInto[proto.User]), r.page, r.pageSize, r.count, SearchUsersRes.Result.Success(""))
          case Left(e) =>
            SearchUsersRes(result = SearchUsersRes.Result.Failure(e.error))
        }
      }

      override def suggestUsers(in: SuggestUsersReq): Future[SuggestUsersRes] =
        userRepository.suggest(in.query).map {
          case Right(r) =>
            SuggestUsersRes(r.items.map(_.transformInto[PropertySuggestion]), SuggestUsersRes.Result.Success(""))
          case Left(e) =>
            SuggestUsersRes(result = SuggestUsersRes.Result.Failure(e.error))
        }
    }
  }
}
