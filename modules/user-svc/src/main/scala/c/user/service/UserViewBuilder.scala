package c.user.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import akka.stream.Materializer
import akka.stream.scaladsl.FlowWithContext
import c.cqrs.offsetstore.OffsetStore
import c.cqrs.processor.CassandraJournalEventProcessor
import c.user.domain._

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

  def getUpdatedUser(event: UserEntity.UserEvent, user: Option[UserRepository.User]): UserRepository.User = {
    import io.scalaland.chimney.dsl._
    val currentUser = user.getOrElse(UserRepository.User(event.entityId, "", "", ""))
    import c.user.domain.proto._
    event match {
      case UserPayloadEvent(entityId, _, payload: UserPayloadEvent.Payload.Created) =>
        val na = payload.value.address.map(_.transformInto[UserRepository.Address])
        currentUser.copy(
          id = entityId,
          username = payload.value.username,
          email = payload.value.email,
          pass = payload.value.pass,
          address = na
        )

      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.PasswordUpdated) =>
        currentUser.copy(pass = payload.value.pass)

      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.EmailUpdated) =>
        currentUser.copy(email = payload.value.email)

      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.AddressUpdated) =>
        val na = payload.value.address.map(_.transformInto[UserRepository.Address])
        currentUser.copy(address = na)

      case UserPayloadEvent(_, _, _: UserPayloadEvent.Payload.Removed) =>
        currentUser.copy(deleted = true)

      case _ => currentUser
    }
  }
}
