package c.user.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{EventEnvelope, Offset, PersistenceQuery}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import c.cqrs.{EventStreamElement, OffsetStore, SingletonActorEntityViewBuilder}
import c.user.domain._

import scala.concurrent.{ExecutionContext, Future}

object UserViewBuilder {

  val UserViewOffsetName = "userView"

  def create(
      userRepository: UserRepository[Future],
      offsetStore: OffsetStore[Offset, Future]
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit = {

    val readJournal = PersistenceQuery(system)
      .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

    val offsetName    = (_: String) => UserViewOffsetName
    val initialOffset = (storedOffset: Option[Offset]) => storedOffset.getOrElse(Offset.timeBasedUUID(readJournal.firstOffset))

    val eventStreamFactory = (name: String, initialOffset: Offset) =>
      readJournal
        .eventsByTag(name, initialOffset)
        .collect {
          case EventEnvelope(offset, _, _, event: UserEntity.UserEvent) => EventStreamElement(offset, event)
        }

    val handleEvent: Flow[EventStreamElement[UserEntity.UserEvent], EventStreamElement[UserEntity.UserEvent], NotUsed] =
      Flow[EventStreamElement[UserEntity.UserEvent]].mapAsync(1) { element =>
        userRepository
          .find(element.event.entityID)
          .flatMap {
            case u @ Some(_) => userRepository.update(getUpdatedUser(element.event, u))
            case None        => userRepository.insert(getUpdatedUser(element.event, None))
          }
          .map(_ => element)
      }

    SingletonActorEntityViewBuilder.create(
      UserPersistentEntity.entityName,
      offsetName,
      initialOffset,
      offsetStore,
      eventStreamFactory,
      handleEvent
    )
  }

  def getUpdatedUser(event: UserEntity.UserEvent, user: Option[UserEntity.User]): UserEntity.User =
    user match {
      case Some(u) => UserEntity.eventApplier(u, event)
      case None    => UserEntity.initialEventApplier(event).getOrElse(UserEntity.eventApplier(UserEntity.User(event.entityID, "", ""), event))
    }
}
