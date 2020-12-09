package com.jc.user.domain

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import com.jc.cqrs.{ BasicPersistentEntityService, EntityService }
import com.jc.user.domain.proto._

import scala.concurrent.{ ExecutionContext, Future }

trait UserService extends EntityService[Future, UserEntity.UserId, User, UserEntity.UserCommand]

object UserService {

  def apply(departmentService: DepartmentService, addressValidationService: AddressValidationService[Future])(
      implicit sharding: ClusterSharding,
      actorSystem: ActorSystem[_],
      askTimeout: Timeout
  ): UserService = new UserServiceImpl(departmentService, addressValidationService)

  private final class UserServiceImpl(departmentService: DepartmentService, addressValidationService: AddressValidationService[Future])(
      implicit val sharding: ClusterSharding,
      val actorSystem: ActorSystem[_],
      val askTimeout: Timeout
  ) extends BasicPersistentEntityService[UserEntity.UserId, User, UserEntity.UserCommand, UserPersistentEntity]
      with UserService {
    implicit val executionContext: ExecutionContext = actorSystem.executionContext
    lazy val persistentEntity                       = UserPersistentEntity(departmentService, addressValidationService)
  }

}
