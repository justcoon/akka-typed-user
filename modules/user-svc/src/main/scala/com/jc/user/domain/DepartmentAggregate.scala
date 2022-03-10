package com.jc.user.domain

import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.{ RecoveryCompleted, RecoveryFailed, SnapshotAdapter }
import akka.persistence.typed.scaladsl.{ EventSourcedBehavior, RetentionCriteria }
import com.jc.cqrs.{
  CommandProcessResult,
  CommandProcessor,
  EntityCommand,
  EventApplier,
  InitialCommandProcessor,
  InitialEventApplier,
  PersistentEntity,
  ShardedEntityEventTagger
}
import com.jc.user.domain
import com.jc.user.domain.proto.{
  Department,
  DepartmentCreatedPayload,
  DepartmentEntityState,
  DepartmentPayloadEvent,
  DepartmentRemovedPayload,
  DepartmentUpdatedPayload
}
import com.jc.user.domain.DepartmentAggregate._

import java.time.Instant

object DepartmentAggregate {

  def apply(): DepartmentAggregate = new DepartmentAggregate

  final val entityName = "department"

  sealed trait DepartmentCommand[R] extends EntityCommand[DepartmentEntity.DepartmentId, Department, R]

  final case class CreateDepartmentCommand(
      entityId: DepartmentEntity.DepartmentId,
      name: String,
      description: String
  ) extends DepartmentCommand[CreateDepartmentReply]

  final case class RemoveDepartmentCommand(
      entityId: DepartmentEntity.DepartmentId
  ) extends DepartmentCommand[RemoveDepartmentReply]

  sealed trait CreateOrUpdateDepartmentReply {
    def entityId: DepartmentEntity.DepartmentId
  }

  final case class GetDepartmentCommand(entityId: DepartmentEntity.DepartmentId) extends DepartmentCommand[GetDepartmentReply]

  final case class UpdateDepartmentCommand(entityId: DepartmentEntity.DepartmentId, name: String, description: String)
      extends DepartmentCommand[UpdateDepartmentReply]

  sealed trait CreateDepartmentReply extends CreateOrUpdateDepartmentReply

  case class DepartmentCreatedReply(entityId: DepartmentEntity.DepartmentId) extends CreateDepartmentReply

  case class DepartmentCreatedFailedReply(entityId: DepartmentEntity.DepartmentId, error: String) extends CreateDepartmentReply

  case class DepartmentAlreadyExistsReply(entityId: DepartmentEntity.DepartmentId) extends CreateDepartmentReply

  sealed trait RemoveDepartmentReply extends CreateOrUpdateDepartmentReply

  case class DepartmentRemovedReply(entityId: DepartmentEntity.DepartmentId) extends RemoveDepartmentReply

  sealed trait GetDepartmentReply

  case class DepartmentReply(department: Department) extends GetDepartmentReply

  sealed trait UpdateDepartmentReply extends CreateOrUpdateDepartmentReply

  case class DepartmentUpdatedReply(entityId: DepartmentEntity.DepartmentId) extends UpdateDepartmentReply

  case class DepartmentNotExistsReply(entityId: DepartmentEntity.DepartmentId)
      extends GetDepartmentReply
      with RemoveDepartmentReply
      with UpdateDepartmentReply

  implicit val initialCommandProcessor: InitialCommandProcessor[DepartmentCommand, DepartmentEntity.DepartmentEvent] = {
    case cmd: CreateDepartmentCommand =>
      val event =
        DepartmentPayloadEvent(
          cmd.entityId,
          Instant.now,
          DepartmentPayloadEvent.Payload.Created(DepartmentCreatedPayload(cmd.name, cmd.description))
        )
      CommandProcessResult.withCmdEventReply(cmd)(event, DepartmentCreatedReply(cmd.entityId))
    case otherCommand =>
      //      logError(s"Received erroneous initial command $otherCommand for entity")
      CommandProcessResult.withReply(DepartmentNotExistsReply(otherCommand.entityId))
  }

  implicit val commandProcessor: CommandProcessor[Department, DepartmentCommand, DepartmentEntity.DepartmentEvent] =
    (state, command) =>
      command match {
        case cmd: CreateDepartmentCommand =>
          CommandProcessResult.withCmdReply(cmd)(DepartmentAlreadyExistsReply(cmd.entityId))
        case cmd: UpdateDepartmentCommand =>
          val event =
            DepartmentPayloadEvent(
              cmd.entityId,
              Instant.now,
              DepartmentPayloadEvent.Payload.Updated(DepartmentUpdatedPayload(cmd.name, cmd.description))
            )
          CommandProcessResult.withCmdEventReply(cmd)(event, DepartmentUpdatedReply(cmd.entityId))
        case cmd: RemoveDepartmentCommand =>
          val event =
            DepartmentPayloadEvent(cmd.entityId, Instant.now, DepartmentPayloadEvent.Payload.Removed(DepartmentRemovedPayload()))
          CommandProcessResult.withCmdEventReply(cmd)(event, DepartmentRemovedReply(cmd.entityId))
        case cmd: GetDepartmentCommand =>
          CommandProcessResult.withCmdReply(cmd)(DepartmentReply(state))
      }

  implicit val initialEventApplier: InitialEventApplier[Department, DepartmentEntity.DepartmentEvent] = {
    case DepartmentPayloadEvent(entityId, _, payload: DepartmentPayloadEvent.Payload.Created, _) =>
      Some(Department(entityId, payload.value.name, payload.value.description))
    case _ =>
      //      logError(s"Received department event $otherEvent before actual department booking")
      None
  }

  implicit val eventApplier: EventApplier[Department, DepartmentEntity.DepartmentEvent] = (department, event) =>
    event match {
      case DepartmentPayloadEvent(_, _, payload: DepartmentPayloadEvent.Payload.Updated, _) =>
        val newDepartment = department.withName(payload.value.name).withDescription(payload.value.description)
        Some(newDepartment)
      case DepartmentPayloadEvent(_, _, _: DepartmentPayloadEvent.Payload.Removed, _) =>
        None
      case _ =>
        Some(department)
    }

  final val departmentEventTagger = ShardedEntityEventTagger.sharded[DepartmentEntity.DepartmentEvent](3)

}

sealed class DepartmentAggregate()
    extends PersistentEntity[
      DepartmentEntity.DepartmentId,
      Department,
      domain.DepartmentAggregate.DepartmentCommand,
      DepartmentEntity.DepartmentEvent
    ](DepartmentAggregate.entityName) {

  import scala.concurrent.duration._

  override def entityId(command: DepartmentCommand[_]): String = command.entityId.toString

  val snapshotAdapter: SnapshotAdapter[EntityState] = new SnapshotAdapter[EntityState] {
    override def toJournal(state: EntityState): Any =
      state match {
        case Uninitialized       => DepartmentEntityState(None)
        case Initialized(entity) => DepartmentEntityState(Some(entity))
      }

    override def fromJournal(from: Any): EntityState =
      from match {
        case DepartmentEntityState(Some(entity), _) => Initialized(entity)
        case _                                      => Uninitialized
      }
  }

  override def configureEntityBehavior(
      behavior: EventSourcedBehavior[Command, DepartmentEntity.DepartmentEvent, EntityState],
      actorContext: ActorContext[Command]
  ): EventSourcedBehavior[Command, DepartmentEntity.DepartmentEvent, EntityState] =
    behavior
      .receiveSignal {
        case (Initialized(state), RecoveryCompleted) =>
          actorContext.log.info(s"Successful recovery of Department entity ${state.id} in state $state")
        case (Uninitialized, _) =>
          actorContext.log.info(s"Department entity created in uninitialized state")
        case (state, RecoveryFailed(error)) =>
          actorContext.log.error(s"Failed recovery of Department entity in state $state: $error")
      }
      .withTagger(DepartmentAggregate.departmentEventTagger.tags)
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
      .snapshotAdapter(snapshotAdapter)
      .onPersistFailure(
        SupervisorStrategy
          .restartWithBackoff(
            minBackoff = 10.seconds,
            maxBackoff = 60.seconds,
            randomFactor = 0.1
          )
          .withMaxRestarts(5)
      )
}
