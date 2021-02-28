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

object DepartmentViewBuilder {

  val DepartmentViewOffsetNamePrefix = "departmentView"

  val DepartmentViewBuilderName = "departmentViewBuilder"

  val keepAlive: FiniteDuration = 3.seconds

  def create(
      departmentRepository: DepartmentRepository[Future]
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit = {

    val handleEventFlow: FlowWithContext[EventEnvelope[DepartmentEntity.DepartmentEvent], ProjectionContext, Done, ProjectionContext, _] =
      FlowWithContext[EventEnvelope[DepartmentEntity.DepartmentEvent], ProjectionContext]
        .map(_.event)
        .mapAsync(1)(event => processEvent(event, departmentRepository))
        .map(_ => Done)

    CassandraJournalEventProcessor.create(
      DepartmentViewBuilderName,
      DepartmentViewOffsetNamePrefix,
      DepartmentAggregate.departmentEventTagger,
      handleEventFlow,
      keepAlive
    )
  }

  def processEvent(event: DepartmentEntity.DepartmentEvent, departmentRepository: DepartmentRepository[Future])(
      implicit ec: ExecutionContext
  ): Future[Boolean] =
    if (isDepartmentRemoved(event))
      departmentRepository.delete(event.entityId)
    else
      departmentRepository
        .find(event.entityId)
        .flatMap {
          case u @ Some(_) => departmentRepository.update(getUpdatedDepartment(event, u))
          case None        => departmentRepository.insert(getUpdatedDepartment(event, None))
        }

  def isDepartmentRemoved(event: DepartmentEntity.DepartmentEvent): Boolean = {
    import com.jc.user.domain.proto._
    event match {
      case DepartmentPayloadEvent(_, _, _: DepartmentPayloadEvent.Payload.Removed, _) => true
      case _                                                                          => false
    }
  }

  def createDepartment(id: DepartmentEntity.DepartmentId): DepartmentRepository.Department = DepartmentRepository.Department(id, "", "")

  def getUpdatedDepartment(
      event: DepartmentEntity.DepartmentEvent,
      department: Option[DepartmentRepository.Department]
  ): DepartmentRepository.Department = {
    import DepartmentRepository.Department._
    import com.jc.user.domain.proto._

    val currentDepartment = department.getOrElse(createDepartment(event.entityId))

    event match {
      case DepartmentPayloadEvent(_, _, payload: DepartmentPayloadEvent.Payload.Created, _) =>
        nameDescriptionLens.set(currentDepartment)((payload.value.name, payload.value.description))

      case DepartmentPayloadEvent(_, _, payload: DepartmentPayloadEvent.Payload.Updated, _) =>
        nameDescriptionLens.set(currentDepartment)((payload.value.name, payload.value.description))

      case _ => currentDepartment
    }
  }
}
