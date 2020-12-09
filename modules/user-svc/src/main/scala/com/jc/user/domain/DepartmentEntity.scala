package com.jc.user.domain

import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.{ RecoveryCompleted, RecoveryFailed, SnapshotAdapter }
import akka.persistence.typed.scaladsl.{ EventSourcedBehavior, RetentionCriteria }
import com.jc.cqrs.{
  CommandProcessResult,
  CommandProcessor,
  EntityCommand,
  EntityEvent,
  EventApplier,
  InitialCommandProcessor,
  InitialEventApplier,
  PersistentEntity,
  ShardedEntityEventTagger
}
import com.jc.user.domain.proto.{
  Department,
  DepartmentCreatedPayload,
  DepartmentEntityState,
  DepartmentPayloadEvent,
  DepartmentRemovedPayload,
  DepartmentUpdatedPayload
}
import io.circe.{ Decoder, Encoder }
import shapeless.tag
import shapeless.tag.@@

import java.time.Instant

object DepartmentEntity {

  implicit val departmentIdDecoder: Decoder[DepartmentId] = Decoder[String].map(_.asDepartmentId)
  implicit val departmentIdEncoder: Encoder[DepartmentId] = Encoder.encodeString.contramap(identity)

  implicit class DepartmentIdTaggerOps(v: String) {
    val asDepartmentId: DepartmentId = tag[DepartmentIdTag][String](v)

    def as[U]: String @@ U = tag[U][String](v)
  }

  trait DepartmentIdTag

  type DepartmentId = String @@ DepartmentIdTag

  sealed trait DepartmentCommand[R] extends EntityCommand[DepartmentEntity.DepartmentId, Department, R]

  final case class CreateDepartmentCommand(
      entityId: DepartmentId,
      name: String,
      description: String
  ) extends DepartmentCommand[CreateDepartmentReply]

  final case class RemoveDepartmentCommand(
      entityId: DepartmentId
  ) extends DepartmentCommand[RemoveDepartmentReply]

  sealed trait CreateOrUpdateDepartmentReply {
    def entityId: DepartmentId
  }

  final case class GetDepartmentCommand(entityId: DepartmentId) extends DepartmentCommand[GetDepartmentReply]

  final case class UpdateDepartmentCommand(entityId: DepartmentId, name: String, description: String)
      extends DepartmentCommand[UpdateDepartmentReply]

  sealed trait CreateDepartmentReply extends CreateOrUpdateDepartmentReply

  case class DepartmentCreatedReply(entityId: DepartmentId) extends CreateDepartmentReply

  case class DepartmentCreatedFailedReply(entityId: DepartmentId, error: String) extends CreateDepartmentReply

  case class DepartmentAlreadyExistsReply(entityId: DepartmentId) extends CreateDepartmentReply

  sealed trait RemoveDepartmentReply extends CreateOrUpdateDepartmentReply

  case class DepartmentRemovedReply(entityId: DepartmentId) extends RemoveDepartmentReply

  sealed trait GetDepartmentReply

  case class DepartmentReply(department: Department) extends GetDepartmentReply

  sealed trait UpdateDepartmentReply extends CreateOrUpdateDepartmentReply

  case class DepartmentUpdatedReply(entityId: DepartmentId) extends UpdateDepartmentReply

  case class DepartmentNotExistsReply(entityId: DepartmentId)
      extends GetDepartmentReply
      with RemoveDepartmentReply
      with UpdateDepartmentReply

  trait DepartmentEvent extends EntityEvent[DepartmentId]

  implicit val initialCommandProcessor: InitialCommandProcessor[DepartmentCommand, DepartmentEvent] = {
    case CreateDepartmentCommand(entityId, name, description) =>
      val event =
        (DepartmentPayloadEvent(entityId, Instant.now, DepartmentPayloadEvent.Payload.Created(DepartmentCreatedPayload(name, description))))
      CommandProcessResult.withReply(event, DepartmentCreatedReply(entityId))
    case otherCommand =>
      //      logError(s"Received erroneous initial command $otherCommand for entity")
      CommandProcessResult.withReply(DepartmentNotExistsReply(otherCommand.entityId))
  }

  implicit val commandProcessor: CommandProcessor[Department, DepartmentCommand, DepartmentEvent] =
    (state, command) =>
      command match {
        case CreateDepartmentCommand(entityId, _, _) =>
          CommandProcessResult.withReply(DepartmentAlreadyExistsReply(entityId))
        case UpdateDepartmentCommand(entityId, name, description) =>
          val event =
            (DepartmentPayloadEvent(
              entityId,
              Instant.now,
              DepartmentPayloadEvent.Payload.Updated(DepartmentUpdatedPayload(name, description))
            ))
          CommandProcessResult.withReply(event, DepartmentUpdatedReply(entityId))
        case RemoveDepartmentCommand(entityId) =>
          val event =
            (DepartmentPayloadEvent(entityId, Instant.now, DepartmentPayloadEvent.Payload.Removed(DepartmentRemovedPayload())))
          CommandProcessResult.withReply(event, DepartmentRemovedReply(entityId))
        case GetDepartmentCommand(_) =>
          CommandProcessResult.withReply(DepartmentReply(state))
      }

  implicit val initialEventApplier: InitialEventApplier[Department, DepartmentEvent] = {
    case DepartmentPayloadEvent(entityId, _, payload: DepartmentPayloadEvent.Payload.Created, _) =>
      Some(Department(entityId, payload.value.name, payload.value.description))
    case _ =>
      //      logError(s"Received department event $otherEvent before actual department booking")
      None
  }
//
  implicit val eventApplier: EventApplier[Department, DepartmentEvent] = (department, event) =>
    event match {
      case DepartmentPayloadEvent(_, _, payload: DepartmentPayloadEvent.Payload.Updated, _) =>
        val newDepartment = department.withName(payload.value.name).withDescription(payload.value.description)
        Some(newDepartment)
      case DepartmentPayloadEvent(_, _, _: DepartmentPayloadEvent.Payload.Removed, _) =>
        None
      case _ =>
        Some(department)
    }

  final val departmentEventTagger = ShardedEntityEventTagger.sharded[DepartmentEvent](3)

}

sealed class DepartmentPersistentEntity()
    extends PersistentEntity[
      DepartmentEntity.DepartmentId,
      Department,
      DepartmentEntity.DepartmentCommand,
      DepartmentEntity.DepartmentEvent
    ](DepartmentPersistentEntity.entityName) {

  import scala.concurrent.duration._

  def entityIdFromString(id: String): DepartmentEntity.DepartmentId = {
    import DepartmentEntity._
    id.asDepartmentId
  }

  def entityIdToString(id: DepartmentEntity.DepartmentId): String = id.toString

  val snapshotAdapter: SnapshotAdapter[OuterState] = new SnapshotAdapter[OuterState] {
    override def toJournal(state: OuterState): Any =
      state match {
        case Uninitialized       => DepartmentEntityState(None)
        case Initialized(entity) => DepartmentEntityState(Some(entity))
      }

    override def fromJournal(from: Any): OuterState =
      from match {
        case DepartmentEntityState(Some(entity), _) => Initialized(entity)
        case _                                      => Uninitialized
      }
  }

  override def configureEntityBehavior(
      id: DepartmentEntity.DepartmentId,
      behavior: EventSourcedBehavior[Command, DepartmentEntity.DepartmentEvent, OuterState],
      actorContext: ActorContext[Command]
  ): EventSourcedBehavior[Command, DepartmentEntity.DepartmentEvent, OuterState] =
    behavior
      .receiveSignal {
        case (Initialized(state), RecoveryCompleted) =>
          actorContext.log.info(s"Successful recovery of Department entity $id in state $state")
        case (Uninitialized, _) =>
          actorContext.log.info(s"Department entity $id created in uninitialized state")
        case (state, RecoveryFailed(error)) =>
          actorContext.log.error(s"Failed recovery of Department entity $id in state $state: $error")
      }
      .withTagger(DepartmentEntity.departmentEventTagger.tags)
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
      .snapshotAdapter(snapshotAdapter)
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

object DepartmentPersistentEntity {
  def apply(): DepartmentPersistentEntity = new DepartmentPersistentEntity

  val entityName = "department"
}
