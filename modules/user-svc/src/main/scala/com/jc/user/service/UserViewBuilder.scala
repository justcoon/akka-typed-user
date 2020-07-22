package com.jc.user.service

import akka.{ Done, NotUsed }
import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import akka.projection.ProjectionContext
import akka.projection.eventsourced.EventEnvelope
import akka.stream.Materializer
import akka.stream.scaladsl.FlowWithContext
import com.jc.user.domain._
import com.jc.cqrs.offsetstore.OffsetStore
import com.jc.cqrs.processor.{ CassandraJournalEventProcessor, CassandraProjectionJournalEventProcessor }
import com.jc.user.domain.proto.UserPayloadEvent.Payload.Created

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.concurrent.{ ExecutionContext, Future }

object UserViewBuilder {

  val UserViewOffsetNamePrefix = "userView"

  val UserViewBuilderName = "userViewBuilder"

  val keepAlive: FiniteDuration = 3.seconds

  def create(
      userRepository: UserRepository[Future],
      offsetStore: OffsetStore[Offset, Future]
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit = {

    val handleEventFlow: FlowWithContext[UserEntity.UserEvent, Offset, _, Offset, NotUsed] =
      FlowWithContext[UserEntity.UserEvent, Offset].mapAsync(1)(event => processEvent(event, userRepository))

    CassandraJournalEventProcessor.create(
      UserViewBuilderName,
      UserViewOffsetNamePrefix,
      UserEntity.userEventTagger,
      handleEventFlow,
      offsetStore,
      keepAlive
    )
  }

  def createWithProjection(
      userRepository: UserRepository[Future]
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit = {

    val handleEventFlow: FlowWithContext[EventEnvelope[UserEntity.UserEvent], ProjectionContext, Done, ProjectionContext, _] =
      FlowWithContext[EventEnvelope[UserEntity.UserEvent], ProjectionContext]
        .map(_.event)
        .mapAsync(1)(event => processEvent(event, userRepository))
        .map(_ => Done)

    CassandraProjectionJournalEventProcessor.create(
      UserViewBuilderName,
      UserViewOffsetNamePrefix,
      UserEntity.userEventTagger,
      handleEventFlow,
      keepAlive
    )
  }

  def processEvent(event: UserEntity.UserEvent, userRepository: UserRepository[Future])(implicit ec: ExecutionContext): Future[Boolean] =
    userRepository
      .find(event.entityId)
      .flatMap {
        case u @ Some(_) => userRepository.update(getUpdatedUser(event, u))
        case None        => userRepository.insert(getUpdatedUser(event, None))
      }

  def createUser(id: UserEntity.UserId): UserRepository.User = UserRepository.User(id, "", "", "")

  def getUpdatedUser(event: UserEntity.UserEvent, user: Option[UserRepository.User]): UserRepository.User = {
    import io.scalaland.chimney.dsl._
    import com.jc.user.domain.proto._
    import UserRepository.User._

    val currentUser = user.getOrElse(createUser(event.entityId))

    event match {
      case UserPayloadEvent(_, _, payload: Created, _) =>
        val na = payload.value.address.map(_.transformInto[UserRepository.Address])
        usernameEmailPassAddressLens.set(currentUser)((payload.value.username, payload.value.email, payload.value.pass, na))

      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.PasswordUpdated, _) =>
        passLens.set(currentUser)(payload.value.pass)

      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.EmailUpdated, _) =>
        emailLens.set(currentUser)(payload.value.email)

      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.AddressUpdated, _) =>
        val na = payload.value.address.map(_.transformInto[UserRepository.Address])
        addressLens.set(currentUser)(na)

      case UserPayloadEvent(_, _, _: UserPayloadEvent.Payload.Removed, _) =>
        deletedLens.set(currentUser)(true)

      case _ => currentUser
    }
  }
}
