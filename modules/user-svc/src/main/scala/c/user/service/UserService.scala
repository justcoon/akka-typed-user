package c.user.service

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import c.cqrs.TypedActorEntityService
import c.user.domain.{ SimpleAddressValidator, UserEntity, UserPersistentEntity }

import scala.concurrent.{ ExecutionContext, Future }
import c.user.domain.proto._

class UserService()(
    implicit val sharding: ClusterSharding,
    val actorSystem: ActorSystem[_],
    val askTimeout: Timeout
) extends TypedActorEntityService[UserEntity.UserId, User, UserEntity.UserCommand, UserPersistentEntity]
    //  with UserService[Future]
    {
  implicit val executionContext: ExecutionContext = actorSystem.executionContext
  lazy val persistentEntity                       = UserPersistentEntity(new SimpleAddressValidator)

}
