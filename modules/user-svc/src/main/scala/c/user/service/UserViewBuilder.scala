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
      case UserCreatedEvent(aid, u, e, p, a, _) =>
        val na = a.map(_.transformInto[UserRepository.Address])
        currentUser.copy(id = aid, username = u, email = e, pass = p, address = na)

      case UserPasswordChangedEvent(_, p, _) =>
        currentUser.copy(pass = p)

      case UserEmailChangedEvent(_, e, _) =>
        currentUser.copy(email = e)

      case UserAddressChangedEvent(_, a, _) =>
        val na = a.map(_.transformInto[UserRepository.Address])
        currentUser.copy(address = na)

      case UserRemovedEvent(_, _) =>
        currentUser.copy(deleted = true)

      case _ => currentUser
    }
  }
}
