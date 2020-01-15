package c.cqrs

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.util.Timeout
import c.cqrs.PersistentEntity.CommandExpectingReply

import scala.concurrent.Future

trait TypedActorEntityService[ID, S, C[R] <: EntityCommand[ID, S, R], Entity <: PersistentEntity[ID, S, C, _]] {
  implicit def sharding: ClusterSharding

  implicit def actorSystem: ActorSystem[_]

  implicit def askTimeout: Timeout

  def persistentEntity: Entity

  sharding.init(
    Entity(persistentEntity.entityTypeKey) { entityContext =>
      Behaviors.setup { actorContext =>
        persistentEntity.eventSourcedEntity(entityContext, actorContext)
      }
    }
  )

  def sendCommand[R](command: C[R]): Future[R] =
    entityFor(command.entityID) ? CommandExpectingReply(command)

  private def entityFor(id: ID) =
    sharding.entityRefFor(persistentEntity.entityTypeKey, persistentEntity.entityIDToString(id))
}
