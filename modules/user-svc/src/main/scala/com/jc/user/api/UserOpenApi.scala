package com.jc.user.api

import akka.Done
import akka.actor.{ ActorSystem, CoordinatedShutdown }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpHeader, IllegalRequestException, StatusCodes }
import akka.http.scaladsl.server.{ Directives, Route }
import akka.http.scaladsl.util.FastFuture
import akka.util.Timeout
import com.jc.auth.JwtAuthenticator
import com.jc.user.api.openapi.definitions.{ Address, Department, PropertySuggestion, User, UserSearchResponse, UserSuggestResponse }
import com.jc.user.api.openapi.user.{ UserHandler, UserResource }
import com.jc.user.config.HttpApiConfig
import com.jc.user.domain.{ proto, DepartmentEntity, DepartmentService, UserEntity, UserService }
import com.jc.user.service.{ DepartmentRepository, SearchRepository, UserRepository }
import org.slf4j.LoggerFactory
import sttp.tapir.swagger.akkahttp.SwaggerAkka

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.io.Source
import scala.util.{ Failure, Success }

object UserOpenApi {

  private final object BindFailure extends CoordinatedShutdown.Reason

  def server(
      userService: UserService,
      userRepository: UserRepository[Future],
      departmentService: DepartmentService,
      departmentRepository: DepartmentRepository[Future],
      jwtAuthenticator: JwtAuthenticator[String],
      shutdown: CoordinatedShutdown,
      config: HttpApiConfig
  )(implicit askTimeout: Timeout, ec: ExecutionContext, mat: akka.stream.Materializer, sys: ActorSystem): Unit = {
    import eu.timepit.refined.auto._

    val log = LoggerFactory.getLogger(this.getClass)

    val userApiRoutes =
      route(userService, userRepository, departmentService, departmentRepository, jwtAuthenticator)(config.repositoryTimeout, ec, mat)

    val docRoutes = docRoute()

    val restApiRoutes = Directives.concat(userApiRoutes, docRoutes)

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

  def docRoute(): Route = {
    val yaml = Source.fromResource("UserOpenApi.yaml").mkString
    new SwaggerAkka(yaml).routes
  }

  def route(
      userService: UserService,
      userRepository: UserRepository[Future],
      departmentService: DepartmentService,
      departmentRepository: DepartmentRepository[Future],
      jwtAuthenticator: JwtAuthenticator[String]
  )(implicit askTimeout: Timeout, ec: ExecutionContext, mat: akka.stream.Materializer): Route =
    UserResource.routes(
      handler(userService, userRepository, departmentService, departmentRepository, jwtAuthenticator),
      _ => Directives.extractRequest.map(req => req.headers)
    )

  def handler(
      userService: UserService,
      userRepository: UserRepository[Future],
      departmentService: DepartmentService,
      departmentRepository: DepartmentRepository[Future],
      jwtAuthenticator: JwtAuthenticator[String]
  )(implicit askTimeout: Timeout, ec: ExecutionContext): UserHandler[Seq[HttpHeader]] = {

    import io.scalaland.chimney.dsl._

    new UserHandler[Seq[HttpHeader]] {

      def authenticated[R](headers: Seq[HttpHeader])(fn: String => Future[R]): Future[R] = {
        val maybeSubject =
          for {
            header  <- headers.find(h => h.is(JwtAuthenticator.AuthHeader.toLowerCase))
            subject <- jwtAuthenticator.authenticated(header.value)
          } yield subject

        maybeSubject match {
          case Some(subject) => fn(subject)
          case None          => FastFuture.failed(IllegalRequestException(StatusCodes.Unauthorized))
        }
      }

//      override def createDepartment(respond: UserResource.CreateDepartmentResponse.type)(body: Department)(
//          extracted: Seq[HttpHeader]
//      ): Future[UserResource.CreateDepartmentResponse] = {
//        import DepartmentEntity._
//        val id  = body.id.asDepartmentId
//        val cmd = body.into[DepartmentEntity.CreateDepartmentCommand].withFieldConst(_.entityId, id).transform
//        departmentService.sendCommand(cmd).map {
//          case reply: DepartmentEntity.DepartmentCreatedReply =>
//            UserResource.CreateDepartmentResponseOK(reply.entityId)
//          case reply: DepartmentEntity.DepartmentAlreadyExistsReply =>
//            UserResource.CreateDepartmentResponseBadRequest("Department already exits")
//          case reply: DepartmentEntity.DepartmentCreatedFailedReply =>
//            UserResource.CreateDepartmentResponseBadRequest(s"Department create error (${reply.error})")
//        }
//      }
//
//      override def updateDepartment(respond: UserResource.UpdateDepartmentResponse.type)(id: String, body: Department)(
//          extracted: Seq[HttpHeader]
//      ): Future[UserResource.UpdateDepartmentResponse] = ???
//
//      override def deleteDepartment(respond: UserResource.DeleteDepartmentResponse.type)(id: String)(
//          extracted: Seq[HttpHeader]
//      ): Future[UserResource.DeleteDepartmentResponse] = ???

      override def getDepartment(respond: UserResource.GetDepartmentResponse.type)(id: String)(
          extracted: Seq[HttpHeader]
      ): Future[UserResource.GetDepartmentResponse] = {
        import DepartmentEntity._
        departmentRepository.find(id.asDepartmentId).map {
          case Some(r) =>
            UserResource.GetDepartmentResponseOK(r.transformInto[Department])
          case _ => UserResource.GetDepartmentResponseNotFound
        }
      }

      override def getDepartments(respond: UserResource.GetDepartmentsResponse.type)()(
          extracted: Seq[HttpHeader]
      ): Future[UserResource.GetDepartmentsResponse] =
        departmentRepository.findAll().map(r => UserResource.GetDepartmentsResponseOK(r.map(_.transformInto[Department]).toVector))

      override def createUser(
          respond: UserResource.CreateUserResponse.type
      )(body: User)(extracted: Seq[HttpHeader]): Future[UserResource.CreateUserResponse] = {
        import UserEntity._
        import com.jc.user.domain.DepartmentEntity._
        val id = body.id.getOrElse(body.username).asUserId
        val cmd = body
          .into[UserEntity.CreateUserCommand]
          .withFieldConst(_.entityId, id)
          .withFieldComputed(
            _.department,
            u => u.department.map(_.into[proto.DepartmentRef].withFieldComputed(_.id, _.id.asDepartmentId).transform)
          )
          .transform
        userService.sendCommand(cmd).map {
          case reply: UserEntity.UserCreatedReply => UserResource.CreateUserResponseOK(reply.entityId)
          case reply: UserEntity.UserCreatedFailedReply =>
            UserResource.CreateUserResponseBadRequest(s"User register error (${reply.error})")
          case _: UserEntity.UserAlreadyExistsReply => UserResource.CreateUserResponseBadRequest("User already exits")
        }
      }

      override def getUsers(
          respond: UserResource.GetUsersResponse.type
      )()(extracted: Seq[HttpHeader]): Future[UserResource.GetUsersResponse] =
        userRepository.findAll().map(r => UserResource.GetUsersResponseOK(r.map(_.transformInto[User]).toVector))

      override def getUser(
          respond: UserResource.GetUserResponse.type
      )(id: String)(extracted: Seq[HttpHeader]): Future[UserResource.GetUserResponse] = {
        import UserEntity._
        userRepository.find(id.asUserId).map {
          case Some(r) =>
            UserResource.GetUserResponseOK(r.transformInto[User])
          case _ => UserResource.GetUserResponseNotFound
        }
      }

      override def updateUserAddress(
          respond: UserResource.UpdateUserAddressResponse.type
      )(id: String, body: Address)(extracted: Seq[HttpHeader]): Future[UserResource.UpdateUserAddressResponse] =
        authenticated(extracted) { _ =>
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
      )(id: String)(extracted: Seq[HttpHeader]): Future[UserResource.DeleteUserAddressResponse] =
        authenticated(extracted) { _ =>
          import UserEntity._
          val cmd = UserEntity.ChangeUserAddressCommand(id.asUserId, None)
          userService.sendCommand(cmd).map {
            case reply: UserEntity.UserAddressChangedReply => UserResource.DeleteUserAddressResponseOK(reply.entityId)
            case reply: UserEntity.UserAddressChangedFailedReply =>
              UserResource.DeleteUserAddressResponseBadRequest(s"User address update error (${reply.error})")
            case _: UserEntity.UserNotExistsReply => UserResource.DeleteUserAddressResponseBadRequest("User not exists")
          }
        }

      override def deleteUser(
          respond: UserResource.DeleteUserResponse.type
      )(id: String)(extracted: Seq[HttpHeader]): Future[UserResource.DeleteUserResponse] =
        authenticated(extracted) { _ =>
          import UserEntity._
          val cmd = UserEntity.RemoveUserCommand(id.asUserId)
          userService.sendCommand(cmd).map {
            case reply: UserEntity.UserRemovedReply => UserResource.DeleteUserResponseOK(reply.entityId)
            case _: UserEntity.UserNotExistsReply   => UserResource.DeleteUserResponseNotFound("User not exists")
          }
        }

      override def searchUsers(
          respond: UserResource.SearchUsersResponse.type
      )(
          query: Option[String],
          page: Int,
          pageSize: Int,
          sort: Option[Iterable[String]] = None
      )(extracted: Seq[HttpHeader]): Future[UserResource.SearchUsersResponse] = {
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

      override def suggestUsers(
          respond: UserResource.SuggestUsersResponse.type
      )(query: Option[String])(extracted: Seq[HttpHeader]): Future[UserResource.SuggestUsersResponse] =
        userRepository.suggest(query.getOrElse("")).map {
          case Right(res) =>
            val items = res.items.map(_.transformInto[PropertySuggestion]).toVector
            UserResource.SuggestUsersResponseOK(UserSuggestResponse(items))
          case Left(e) =>
            UserResource.SuggestUsersResponseBadRequest(e.error)
        }
    }
  }

  // TODO improve parsing
  // sort - field:order, examples: username:asc, email:desc
  def toFieldSort(sort: String): SearchRepository.FieldSort =
    sort.split(":").toList match {
      case p :: o :: Nil =>
        SearchRepository.FieldSort(p, o.toLowerCase != "desc")
      case _ =>
        SearchRepository.FieldSort(sort, true)
    }
}
