package c.user.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{ EventEnvelope, Offset, PersistenceQuery }
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import c.cqrs.{ EventProcessor, EventProcessorStream, EventStreamElement, OffsetStore }
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

    val readJournal = PersistenceQuery(system)
      .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

    val offsetName    = (shardId: String) => s"$UserViewOffsetNamePrefix-$shardId"
    val initialOffset = (storedOffset: Option[Offset]) => storedOffset.getOrElse(Offset.timeBasedUUID(readJournal.firstOffset))

    val eventStreamFactory = (shardId: String, initialOffset: Offset) =>
      readJournal
        .eventsByTag(shardId, initialOffset)
        .collect {
          case EventEnvelope(offset, _, _, event: UserEntity.UserEvent) => EventStreamElement(offset, event)
        }

    val handleEvent: Flow[EventStreamElement[UserEntity.UserEvent], EventStreamElement[UserEntity.UserEvent], NotUsed] =
      Flow[EventStreamElement[UserEntity.UserEvent]].mapAsync(1) { element =>
        userRepository
          .find(element.event.entityId)
          .flatMap {
            case u @ Some(_) => userRepository.update(getUpdatedUser(element.event, u))
            case None        => userRepository.insert(getUpdatedUser(element.event, None))
          }
          .map(_ => element)
      }

    val eventProcessorStream: String => EventProcessorStream[UserEntity.UserEvent] = shardId =>
      EventProcessorStream.create(
        shardId,
        offsetName,
        initialOffset,
        offsetStore,
        eventStreamFactory,
        handleEvent
      )

    EventProcessor.create(UserViewBuilderName, UserEntity.userEventTagger, eventProcessorStream, keepAlive)
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
