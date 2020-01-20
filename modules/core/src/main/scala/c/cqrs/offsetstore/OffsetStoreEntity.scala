package c.cqrs.offsetstore

import java.time.Instant

import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.query.Offset
import akka.persistence.typed.scaladsl.{ EventSourcedBehavior, RetentionCriteria }
import akka.persistence.typed.{ RecoveryCompleted, RecoveryFailed }
import c.cqrs._
import c.cqrs.offsetstore.proto._
import io.circe.{ Decoder, Encoder }
import shapeless.tag
import shapeless.tag.@@

object OffsetStoreEntity {

  implicit val offsetStoreIdDecoder: Decoder[OffsetStoreId] = Decoder[String].map(_.asOffsetStoreId)
  implicit val offsetStoreIdEncoder: Encoder[OffsetStoreId] = Encoder.encodeString.contramap(identity)

  implicit class OffsetStoreIdTaggerOps(v: String) {
    val asOffsetStoreId: OffsetStoreId = tag[OffsetStoreIdTag][String](v)

    def as[U]: String @@ U = tag[U][String](v)
  }

  trait OffsetStoreIdTag

  type OffsetStoreId = String @@ OffsetStoreIdTag

  sealed trait OffsetStoreCommand[R] extends EntityCommand[OffsetStoreEntity.OffsetStoreId, OffsetStore, R]

  final case class CreateOrUpdateOffsetStoreCommand(
      entityId: OffsetStoreId,
      offset: Offset
  ) extends OffsetStoreCommand[CreateOrUpdateOffsetStoreReply] {

    override def initializedReply: OffsetStore => CreateOrUpdateOffsetStoreReply = _ => OffsetStoreUpdatedReply(entityId)

    override def uninitializedReply: CreateOrUpdateOffsetStoreReply = OffsetStoreCreatedReply(entityId)
  }

  final case class GetOffsetStoreCommand(entityId: OffsetStoreId) extends OffsetStoreCommand[GetOffsetStoreReply] {

    override def initializedReply: OffsetStore => GetOffsetStoreReply = offsetStore => OffsetStoreReply(offsetStore)

    override def uninitializedReply: GetOffsetStoreReply = OffsetStoreNotExistsReply(entityId)
  }

  sealed trait CreateOrUpdateOffsetStoreReply

  case class OffsetStoreCreatedReply(entityId: OffsetStoreId) extends CreateOrUpdateOffsetStoreReply

  case class OffsetStoreUpdatedReply(entityId: OffsetStoreId) extends CreateOrUpdateOffsetStoreReply

  sealed trait GetOffsetStoreReply

  case class OffsetStoreReply(offsetStore: OffsetStore) extends GetOffsetStoreReply

  case class OffsetStoreNotExistsReply(entityId: OffsetStoreId) extends GetOffsetStoreReply

  trait OffsetStoreEvent extends EntityEvent[OffsetStoreId]

  implicit val initialCommandProcessor: InitialCommandProcessor[OffsetStoreCommand, OffsetStoreEvent] = {
    case CreateOrUpdateOffsetStoreCommand(entityId, offset) =>
      List(OffsetStoreCreatedEvent(entityId, offset, Instant.now))
    case otherCommand =>
      //      logError(s"Received erroneous initial command $otherCommand for entity")
      Nil
  }

  implicit val commandProcessor: CommandProcessor[OffsetStore, OffsetStoreCommand, OffsetStoreEvent] =
    (state, command) =>
      command match {
        case CreateOrUpdateOffsetStoreCommand(entityId, offset) =>
          List(OffsetStoreUpdatedEvent(entityId, offset, Instant.now))
        case GetOffsetStoreCommand(_) =>
          Nil
        case _ => Nil
      }

  implicit val initialEventApplier: InitialEventApplier[OffsetStore, OffsetStoreEvent] = {
    case OffsetStoreCreatedEvent(entityId, offset, _) =>
      Some(OffsetStore(entityId, offset))
    case otherEvent =>
      //      logError(s"Received offsetStore event $otherEvent before actual offsetStore booking")
      None
  }

  implicit val eventApplier: EventApplier[OffsetStore, OffsetStoreEvent] = (offsetStore, event) =>
    event match {
      case OffsetStoreUpdatedEvent(_, offset, _) =>
        offsetStore.copy(offset = offset)
      case _ =>
        offsetStore
    }

  val offsetStoreEventTagger: OffsetStoreEvent => Set[String] = { _ =>
    val tags = Set(OffsetStorePersistentEntity.entityName)
    tags
  }

}

sealed class OffsetStorePersistentEntity()
    extends PersistentEntity[
      OffsetStoreEntity.OffsetStoreId,
      OffsetStore,
      OffsetStoreEntity.OffsetStoreCommand,
      OffsetStoreEntity.OffsetStoreEvent
    ](OffsetStorePersistentEntity.entityName) {

  import scala.concurrent.duration._

  def entityIdFromString(id: String): OffsetStoreEntity.OffsetStoreId = {
    import OffsetStoreEntity._
    id.asOffsetStoreId
  }

  def entityIdToString(id: OffsetStoreEntity.OffsetStoreId): String = id.toString

  override def configureEntityBehavior(
      id: OffsetStoreEntity.OffsetStoreId,
      behavior: EventSourcedBehavior[Command, OffsetStoreEntity.OffsetStoreEvent, OuterState],
      actorContext: ActorContext[Command]
  ): EventSourcedBehavior[Command, OffsetStoreEntity.OffsetStoreEvent, OuterState] =
    behavior
      .receiveSignal {
        case (Initialized(state), RecoveryCompleted) =>
          actorContext.log.info(s"Successful recovery of OffsetStore entity $id in state $state")
        case (Uninitialized(_), _) =>
          actorContext.log.info(s"OffsetStore entity $id created in uninitialized state")
        case (state, RecoveryFailed(error)) =>
          actorContext.log.error(s"Failed recovery of OffsetStore entity $id in state $state: $error")
      }
      .withTagger(OffsetStoreEntity.offsetStoreEventTagger)
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
      .onPersistFailure(
        SupervisorStrategy
          .restartWithBackoff(
            minBackoff = 10 seconds,
            maxBackoff = 60 seconds,
            randomFactor = 0.1
          )
          .withMaxRestarts(5)
      )
}

object OffsetStorePersistentEntity {
  def apply(): OffsetStorePersistentEntity = new OffsetStorePersistentEntity

  val entityName = "offsetStore"
}
