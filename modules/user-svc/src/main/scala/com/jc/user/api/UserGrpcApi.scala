package com.jc.user.api

import akka.actor.{ ActorSystem, CoordinatedShutdown }
import akka.grpc.scaladsl.Metadata
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.scaladsl.Source
import akka.util.Timeout
import akka.{ Done, NotUsed }
import com.jc.auth.JwtAuthenticator
import com.jc.user.api.proto._
import com.jc.user.config.HttpApiConfig
import com.jc.user.domain.{ proto, DepartmentEntity, DepartmentService, UserEntity, UserService }
import com.jc.user.domain.proto.User
import com.jc.user.service.{ DepartmentRepository, SearchRepository, UserRepository }
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object UserGrpcApi {

  private final object BindFailure extends CoordinatedShutdown.Reason

  def server(
      userService: UserService,
      userRepository: UserRepository[Future],
      departmentService: DepartmentService,
      departmentRepository: DepartmentRepository[Future],
      jwtAuthenticator: JwtAuthenticator[String],
      shutdown: CoordinatedShutdown,
      config: HttpApiConfig
  )(implicit askTimeout: Timeout, ec: ExecutionContext, sys: ActorSystem): Unit = {
    import eu.timepit.refined.auto._

    val log = LoggerFactory.getLogger(this.getClass)
    val grpcApiHandler =
      handler(userService, userRepository, departmentService, departmentRepository, jwtAuthenticator)(config.repositoryTimeout, ec, sys)

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
      departmentService: DepartmentService,
      departmentRepository: DepartmentRepository[Future],
      jwtAuthenticator: JwtAuthenticator[String]
  )(
      implicit askTimeout: Timeout,
      ec: ExecutionContext,
      sys: ActorSystem
  ): HttpRequest => Future[HttpResponse] =
    UserApiServicePowerApiHandler(service(userService, userRepository, departmentService, departmentRepository, jwtAuthenticator))

  def service(
      userService: UserService,
      userRepository: UserRepository[Future],
      departmentService: DepartmentService,
      departmentRepository: DepartmentRepository[Future],
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

      override def createDepartment(in: CreateDepartmentReq, metadata: Metadata): Future[CreateDepartmentRes] = {
        import DepartmentEntity._
        val id  = in.id.asDepartmentId
        val cmd = in.into[DepartmentEntity.CreateDepartmentCommand].withFieldConst(_.entityId, id).transform

        departmentService.sendCommand(cmd).map {
          case reply: DepartmentEntity.DepartmentCreatedReply =>
            CreateDepartmentRes(reply.entityId, CreateDepartmentRes.Result.Success("Department crateted"))
          case reply: DepartmentEntity.DepartmentAlreadyExistsReply =>
            CreateDepartmentRes(reply.entityId, CreateDepartmentRes.Result.Failure("Department already exits"))
          case reply: DepartmentEntity.DepartmentCreatedFailedReply =>
            CreateDepartmentRes(reply.entityId, CreateDepartmentRes.Result.Failure(s"Department create error (${reply.error})"))
        }
      }

      override def updateDepartment(in: UpdateDepartmentReq, metadata: Metadata): Future[UpdateDepartmentRes] = {
        import DepartmentEntity._
        val id  = in.id.asDepartmentId
        val cmd = in.into[DepartmentEntity.UpdateDepartmentCommand].withFieldConst(_.entityId, id).transform

        departmentService.sendCommand(cmd).map {
          case reply: DepartmentEntity.DepartmentUpdatedReply =>
            UpdateDepartmentRes(reply.entityId, UpdateDepartmentRes.Result.Success("Department updated"))
          case reply: DepartmentEntity.DepartmentNotExistsReply =>
            UpdateDepartmentRes(reply.entityId, UpdateDepartmentRes.Result.Failure("Department not exits"))
        }
      }

      override def getDepartment(in: GetDepartmentReq, metadata: Metadata): Future[GetDepartmentRes] = {
        import DepartmentEntity._
        departmentRepository.find(in.id.asDepartmentId).map(r => GetDepartmentRes(r.map(_.transformInto[proto.Department])))
      }

      override def getDepartments(in: GetDepartmentsReq, metadata: Metadata): Future[GetDepartmentsRes] =
        departmentRepository.findAll().map(r => GetDepartmentsRes(r.map(_.transformInto[proto.Department])))

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

      override def removeUser(in: RemoveUserReq, metadata: Metadata): Future[RemoveUserRes] =
        authenticated(metadata) { _ =>
          import UserEntity._
          val cmd = UserEntity.RemoveUserCommand(in.id.asUserId)
          userService.sendCommand(cmd).map {
            case reply: UserEntity.UserRemovedReply =>
              RemoveUserRes(reply.entityId, RemoveUserRes.Result.Success("User removed"))
            case reply: UserEntity.UserNotExistsReply =>
              RemoveUserRes(reply.entityId, RemoveUserRes.Result.Failure("User not exits"))
          }
        }

      override def updateUserEmail(in: UpdateUserEmailReq, metadata: Metadata): Future[UpdateUserEmailRes] =
        authenticated(metadata) { _ =>
          import UserEntity._
          val cmd = UserEntity.ChangeUserEmailCommand(in.id.asUserId, in.email)
          userService.sendCommand(cmd).map {
            case reply: UserEntity.UserEmailChangedReply =>
              UpdateUserEmailRes(reply.entityId, UpdateUserEmailRes.Result.Success("User email updated"))
            case reply: UserEntity.UserNotExistsReply =>
              UpdateUserEmailRes(reply.entityId, UpdateUserEmailRes.Result.Failure("User not exits"))
          }
        }

      override def updateUserPassword(in: UpdateUserPasswordReq, metadata: Metadata): Future[UpdateUserPasswordRes] =
        authenticated(metadata) { _ =>
          import UserEntity._
          val cmd = UserEntity.ChangeUserPasswordCommand(in.id.asUserId, in.pass)
          userService.sendCommand(cmd).map {
            case reply: UserEntity.UserPasswordChangedReply =>
              UpdateUserPasswordRes(reply.entityId, UpdateUserPasswordRes.Result.Success("User password updated"))
            case reply: UserEntity.UserNotExistsReply =>
              UpdateUserPasswordRes(reply.entityId, UpdateUserPasswordRes.Result.Failure("User not exists"))
          }
        }

      override def updateUserAddress(in: UpdateUserAddressReq, metadata: Metadata): Future[UpdateUserAddressRes] =
        authenticated(metadata) { _ =>
          import UserEntity._
          val cmd = UserEntity.ChangeUserAddressCommand(in.id.asUserId, in.address)
          userService.sendCommand(cmd).map {
            case reply: UserEntity.UserAddressChangedReply =>
              UpdateUserAddressRes(reply.entityId, UpdateUserAddressRes.Result.Success("User address updated"))
            case reply: UserEntity.UserNotExistsReply =>
              UpdateUserAddressRes(reply.entityId, UpdateUserAddressRes.Result.Failure("User not exists"))
            case reply: UserEntity.UserAddressChangedFailedReply =>
              UpdateUserAddressRes(reply.entityId, UpdateUserAddressRes.Result.Failure(s"User address update error (${reply.error})"))
          }
        }

      override def updateUserDepartment(in: UpdateUserDepartmentReq, metadata: Metadata): Future[UpdateUserDepartmentRes] =
        authenticated(metadata) { _ =>
          import UserEntity._
          val cmd = UserEntity.ChangeUserDepartmentCommand(in.id.asUserId, in.department)
          userService.sendCommand(cmd).map {
            case reply: UserEntity.UserDepartmentChangedReply =>
              UpdateUserDepartmentRes(reply.entityId, UpdateUserDepartmentRes.Result.Success("User department updated"))
            case reply: UserEntity.UserNotExistsReply =>
              UpdateUserDepartmentRes(reply.entityId, UpdateUserDepartmentRes.Result.Failure("User not exists"))
            case reply: UserEntity.UserDepartmentChangedFailedReply =>
              UpdateUserDepartmentRes(
                reply.entityId,
                UpdateUserDepartmentRes.Result.Failure(s"User department update error (${reply.error})")
              )
          }
        }

      override def getUser(in: GetUserReq, metadata: Metadata): Future[GetUserRes] = {
        import UserEntity._
        userRepository.find(in.id.asUserId).map(r => GetUserRes(r.map(_.transformInto[proto.User])))
      }

      override def getUsers(in: GetUsersReq, metadata: Metadata): Future[GetUsersRes] =
        userRepository.findAll().map(r => GetUsersRes(r.map(_.transformInto[proto.User])))

      override def searchUsers(in: SearchUsersReq, metadata: Metadata): Future[SearchUsersRes] = {
        val ss = in.sorts.map(sort => SearchRepository.FieldSort(sort.field, sort.order.isAsc))
        val q  = if (in.query.isBlank) None else Some(in.query)
        userRepository.search(q, in.page, in.pageSize, ss).map {
          case Right(r) =>
            SearchUsersRes(r.items.map(_.transformInto[proto.User]), r.page, r.pageSize, r.count, SearchUsersRes.Result.Success(""))
          case Left(e) =>
            SearchUsersRes(result = SearchUsersRes.Result.Failure(e.error))
        }
      }

      override def searchUserStream(in: SearchUserStreamReq, metadata: Metadata): Source[User, NotUsed] = {
        val ss = in.sorts.map(sort => SearchRepository.FieldSort(sort.field, sort.order.isAsc))

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
