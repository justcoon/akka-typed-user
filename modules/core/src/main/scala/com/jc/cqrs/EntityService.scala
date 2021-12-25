package com.jc.cqrs

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity }
import akka.util.Timeout
import com.jc.cqrs.BasicPersistentEntity.CommandExpectingReply

import scala.concurrent.Future

trait EntityService[F[_], ID, S, C[R] <: EntityCommand[ID, S, R]] {
  def sendCommand[R](command: C[R]): F[R]
}

trait BasicPersistentEntityService[ID, S, C[R] <: EntityCommand[ID, S, R], Entity <: BasicPersistentEntity[ID, S, C, _]]
    extends EntityService[Future, ID, S, C] {
  implicit def sharding: ClusterSharding

  implicit def actorSystem: ActorSystem[_]

  implicit def askTimeout: Timeout

  def persistentEntity: Entity

  sharding.init(
    Entity(persistentEntity.entityTypeKey) { entityContext =>
      Behaviors.setup(actorContext => persistentEntity.eventSourcedEntity(entityContext, actorContext))
    }
  )

  override def sendCommand[R](command: C[R]): Future[R] =
    entityFor(command) ? CommandExpectingReply(command)

  private def entityFor[R](command: C[R]) =
    sharding.entityRefFor(persistentEntity.entityTypeKey, persistentEntity.entityId(command))
}
