package com.jc.user.api

import java.time.Clock

import akka.{ Done, NotUsed }
import akka.actor.{ ActorSystem, CoordinatedShutdown }
import akka.grpc.scaladsl.Metadata
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.Timeout
import com.jc.auth.{ JwtAuthenticator, PdiJwtAuthenticator }
import com.jc.user.api.proto._
import com.jc.user.config.HttpApiConfig
import com.jc.user.domain.UserEntity
import com.jc.user.domain.proto
import com.jc.user.domain.proto.User
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
      jwtAuthenticator: JwtAuthenticator[String],
      shutdown: CoordinatedShutdown,
      config: HttpApiConfig
  )(implicit askTimeout: Timeout, ec: ExecutionContext, sys: ActorSystem): Unit = {
    import eu.timepit.refined.auto._

    val log            = LoggerFactory.getLogger(this.getClass)
    val grpcApiHandler = handler(userService, userRepository, jwtAuthenticator)(config.repositoryTimeout, ec, sys)

    Http(sys)
      .newServerAt(config.address, config.port)
      .bind(grpcApiHandler)
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
      userRepository: UserRepository[Future],
      jwtAuthenticator: JwtAuthenticator[String]
  )(
      implicit askTimeout: Timeout,
      ec: ExecutionContext,
      sys: ActorSystem
  ): HttpRequest => Future[HttpResponse] =
    UserApiServicePowerApiHandler(service(userService, userRepository, jwtAuthenticator))

  def service(
      userService: UserService,
      userRepository: UserRepository[Future],
      jwtAuthenticator: JwtAuthenticator[String]
  )(implicit askTimeout: Timeout, ec: ExecutionContext): UserApiServicePowerApi = {

    import io.scalaland.chimney.dsl._

    new UserApiServicePowerApi {

      def authenticated[R](metadata: Metadata)(fn: String => Future[R]): Future[R] = {
        val maybeSubject =
          for {
            header  <- metadata.getText(JwtAuthenticator.AuthHeader)
            subject <- jwtAuthenticator.authenticated(header)
          } yield subject

        maybeSubject match {
          case Some(subject) => fn(subject)
          case None          => Future.failed(new akka.grpc.GrpcServiceException(io.grpc.Status.UNAUTHENTICATED, metadata))
        }
      }

      override def registerUser(in: RegisterUserReq, metadata: Metadata): Future[RegisterUserRes] = {
        import UserEntity._
        val id  = in.username.asUserId
        val cmd = in.into[UserEntity.CreateUserCommand].withFieldConst(_.entityId, id).transform

        userService.sendCommand(cmd).map {
          case reply: UserEntity.UserCreatedReply =>
            RegisterUserRes(reply.entityId, RegisterUserRes.Result.Success("User registered"))
          case reply: UserEntity.UserAlreadyExistsReply =>
            RegisterUserRes(reply.entityId, RegisterUserRes.Result.Failure("User already exits"))
          case reply: UserEntity.UserCreatedFailedReply =>
            RegisterUserRes(reply.entityId, RegisterUserRes.Result.Failure(s"User register error (${reply.error})"))
        }
      }

      override def updateUserEmail(in: UpdateEmailReq, metadata: Metadata): Future[UpdateEmailRes] =
        authenticated(metadata) { _ =>
          import UserEntity._
          val cmd = UserEntity.ChangeUserEmailCommand(in.id.asUserId, in.email)
          userService.sendCommand(cmd).map {
            case reply: UserEntity.UserEmailChangedReply =>
              UpdateEmailRes(reply.entityId, UpdateEmailRes.Result.Success("User email updated"))
            case reply: UserEntity.UserNotExistsReply =>
              UpdateEmailRes(reply.entityId, UpdateEmailRes.Result.Failure("User not exits"))
          }
        }

      override def updateUserPassword(in: UpdatePasswordReq, metadata: Metadata): Future[UpdatePasswordRes] =
        authenticated(metadata) { _ =>
          import UserEntity._
          val cmd = UserEntity.ChangeUserPasswordCommand(in.id.asUserId, in.pass)
          userService.sendCommand(cmd).map {
            case reply: UserEntity.UserPasswordChangedReply =>
              UpdatePasswordRes(reply.entityId, UpdatePasswordRes.Result.Success("User password updated"))
            case reply: UserEntity.UserNotExistsReply =>
              UpdatePasswordRes(reply.entityId, UpdatePasswordRes.Result.Failure("User not exists"))
          }
        }

      override def updateUserAddress(in: UpdateAddressReq, metadata: Metadata): Future[UpdateAddressRes] =
        authenticated(metadata) { _ =>
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

      override def getUser(in: GetUserReq, metadata: Metadata): Future[GetUserRes] = {
        import UserEntity._
        userRepository.find(in.id.asUserId).map(r => GetUserRes(r.map(_.transformInto[proto.User])))
      }

      override def getUsers(in: GetUsersReq, metadata: Metadata): Future[GetUsersRes] =
        userRepository.findAll().map(r => GetUsersRes(r.map(_.transformInto[proto.User])))

      override def searchUsers(in: SearchUsersReq, metadata: Metadata): Future[SearchUsersRes] = {
        val ss = in.sorts.map(sort => (sort.field, sort.order.isAsc))
        val q  = if (in.query.isBlank) None else Some(in.query)
        userRepository.search(q, in.page, in.pageSize, ss).map {
          case Right(r) =>
            SearchUsersRes(r.items.map(_.transformInto[proto.User]), r.page, r.pageSize, r.count, SearchUsersRes.Result.Success(""))
          case Left(e) =>
            SearchUsersRes(result = SearchUsersRes.Result.Failure(e.error))
        }
      }

      override def searchUserStream(in: SearchUserStreamReq, metadata: Metadata): Source[User, NotUsed] = {
        val ss = in.sorts.map(sort => (sort.field, sort.order.isAsc))

        val q = if (in.query.isBlank) None else Some(in.query)

        val pageInitial = 0
        val pageSize    = 20

        Source
          .unfoldAsync(pageInitial) { page =>
            val res = userRepository.search(q, page, pageSize, ss).map {
              case Right(r) =>
                if ((r.page * r.pageSize) < r.count || r.items.nonEmpty) Some((r.page + 1, r.items))
                else None
              case Left(e) =>
                throw new akka.grpc.GrpcServiceException(io.grpc.Status.INTERNAL.withDescription(e.error), metadata)
            }

            res
          }
          .mapConcat(identity)
          .map(_.transformInto[proto.User])
      }

      override def suggestUsers(in: SuggestUsersReq, metadata: Metadata): Future[SuggestUsersRes] =
        userRepository.suggest(in.query).map {
          case Right(r) =>
            SuggestUsersRes(r.items.map(_.transformInto[PropertySuggestion]), SuggestUsersRes.Result.Success(""))
          case Left(e) =>
            SuggestUsersRes(result = SuggestUsersRes.Result.Failure(e.error))
        }

    }
  }
}
