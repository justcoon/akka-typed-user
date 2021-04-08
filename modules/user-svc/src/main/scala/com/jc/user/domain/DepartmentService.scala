package com.jc.user.domain

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import com.jc.cqrs.{ BasicPersistentEntityService, EntityService }
import com.jc.user.domain.proto._

import scala.concurrent.{ ExecutionContext, Future }

trait DepartmentService extends EntityService[Future, DepartmentEntity.DepartmentId, Department, DepartmentAggregate.DepartmentCommand]

object DepartmentService {

  def apply()(implicit sharding: ClusterSharding, actorSystem: ActorSystem[_], askTimeout: Timeout): DepartmentService =
    new DepartmentServiceImpl()

  private final class DepartmentServiceImpl()(implicit
      val sharding: ClusterSharding,
      val actorSystem: ActorSystem[_],
      val askTimeout: Timeout
  ) extends BasicPersistentEntityService[
        DepartmentEntity.DepartmentId,
        Department,
        DepartmentAggregate.DepartmentCommand,
        DepartmentAggregate
      ]
      with DepartmentService {
    implicit val executionContext: ExecutionContext = actorSystem.executionContext
    lazy val persistentEntity                       = DepartmentAggregate()
  }

}
