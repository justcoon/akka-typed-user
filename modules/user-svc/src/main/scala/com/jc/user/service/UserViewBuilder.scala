package com.jc.user.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import akka.stream.Materializer
import akka.stream.scaladsl.FlowWithContext
import com.jc.user.domain._
import com.jc.cqrs.offsetstore.OffsetStore
import com.jc.cqrs.processor.CassandraJournalEventProcessor
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

    val handleEvent: FlowWithContext[UserEntity.UserEvent, Offset, _, Offset, NotUsed] =
      FlowWithContext[UserEntity.UserEvent, Offset].mapAsync(1) { event =>
        userRepository
          .find(event.entityId)
          .flatMap {
            case u @ Some(_) => userRepository.update(getUpdatedUser(event, u))
            case None        => userRepository.insert(getUpdatedUser(event, None))
          }
      }

    CassandraJournalEventProcessor.create(
      UserViewBuilderName,
      UserViewOffsetNamePrefix,
      UserEntity.userEventTagger,
      handleEvent,
      offsetStore,
      keepAlive
    )
  }

  def createUser(id: UserEntity.UserId): UserRepository.User = UserRepository.User(id, "", "", "")

  def getUpdatedUser(event: UserEntity.UserEvent, user: Option[UserRepository.User]): UserRepository.User = {
    import io.scalaland.chimney.dsl._
    import com.jc.user.domain.proto._
    import UserRepository.User._

    val currentUser = user.getOrElse(createUser(event.entityId))

    event match {
      case UserPayloadEvent(_, _, payload: Created) =>
        val na = payload.value.address.map(_.transformInto[UserRepository.Address])
        usernameEmailPassAddressLens.set(currentUser)(
          payload.value.username,
          payload.value.email,
          payload.value.pass,
          na
        )

      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.PasswordUpdated) =>
        passLens.set(currentUser)(payload.value.pass)

      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.EmailUpdated) =>
        emailLens.set(currentUser)(payload.value.email)

      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.AddressUpdated) =>
        val na = payload.value.address.map(_.transformInto[UserRepository.Address])
        addressLens.set(currentUser)(na)

      case UserPayloadEvent(_, _, _: UserPayloadEvent.Payload.Removed) =>
        deletedLens.set(currentUser)(true)

      case _ => currentUser
    }
  }
}
