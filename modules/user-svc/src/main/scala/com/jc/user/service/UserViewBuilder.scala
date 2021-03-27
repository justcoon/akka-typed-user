package com.jc.user.service

import akka.Done
import akka.actor.typed.ActorSystem
import akka.projection.ProjectionContext
import akka.projection.eventsourced.EventEnvelope
import akka.stream.Materializer
import akka.stream.scaladsl.FlowWithContext
import com.jc.cqrs.processor.CassandraJournalEventProcessor
import com.jc.user.domain._

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.concurrent.{ ExecutionContext, Future }

object UserViewBuilder {

  val UserViewOffsetNamePrefix = "userView"

  val UserViewBuilderName = "userViewBuilder"

  val keepAlive: FiniteDuration = 3.seconds

  def create(
      userRepository: UserRepository[Future],
      keepAliveInterval: FiniteDuration = UserViewBuilder.keepAlive
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit = {

    val handleEventFlow: FlowWithContext[EventEnvelope[UserEntity.UserEvent], ProjectionContext, Done, ProjectionContext, _] =
      FlowWithContext[EventEnvelope[UserEntity.UserEvent], ProjectionContext]
        .mapAsync(1)(event => processEvent(event.event, userRepository))
        .map(_ => Done)

    CassandraJournalEventProcessor.create(
      UserViewBuilderName,
      UserViewOffsetNamePrefix,
      UserAggregate.userEventTagger,
      handleEventFlow,
      keepAliveInterval
    )
  }

  def processEvent(event: UserEntity.UserEvent, userRepository: UserRepository[Future])(implicit ec: ExecutionContext): Future[Boolean] =
    if (isUserRemoved(event))
      userRepository.delete(event.entityId)
    else
      userRepository
        .find(event.entityId)
        .flatMap {
          case u @ Some(_) => userRepository.update(getUpdatedUser(event, u))
          case None        => userRepository.insert(getUpdatedUser(event, None))
        }

  def isUserRemoved(event: UserEntity.UserEvent): Boolean = {
    import com.jc.user.domain.proto._
    event match {
      case UserPayloadEvent(_, _, _: UserPayloadEvent.Payload.Removed, _) => true
      case _                                                              => false
    }
  }

  def createUser(id: UserEntity.UserId): UserRepository.User = UserRepository.User(id, "", "", "")

  def getUpdatedUser(event: UserEntity.UserEvent, user: Option[UserRepository.User]): UserRepository.User = {
    import UserRepository.User._
    import com.jc.user.domain.proto._
    import io.scalaland.chimney.dsl._

    val currentUser = user.getOrElse(createUser(event.entityId))

    event match {
      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.Created, _) =>
        val na = payload.value.address.map(_.transformInto[UserRepository.Address])
        val nd = payload.value.department.map(_.transformInto[UserRepository.Department])
        usernameEmailPassAddressDepartmentLens.set(currentUser)((payload.value.username, payload.value.email, payload.value.pass, na, nd))

      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.PasswordUpdated, _) =>
        passLens.set(currentUser)(payload.value.pass)

      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.EmailUpdated, _) =>
        emailLens.set(currentUser)(payload.value.email)

      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.AddressUpdated, _) =>
        val na = payload.value.address.map(_.transformInto[UserRepository.Address])
        addressLens.set(currentUser)(na)

      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.DepartmentUpdated, _) =>
        val nd = payload.value.department.map(_.transformInto[UserRepository.Department])
        departmentLens.set(currentUser)(nd)

      case _ => currentUser
    }
  }
}
