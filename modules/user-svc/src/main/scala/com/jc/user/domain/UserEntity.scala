package com.jc.user.domain

import akka.Done

import java.time.Instant
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.ActorContext
import akka.http.scaladsl.util.FastFuture
import akka.persistence.typed.scaladsl.{ EventSourcedBehavior, ReplyEffect, RetentionCriteria }
import akka.persistence.typed.{ RecoveryCompleted, RecoveryFailed, SnapshotAdapter }
import cats.data.{ Validated, ValidatedNec }
import cats.implicits._
import com.github.t3hnar.bcrypt._
import com.jc.cqrs._
import com.jc.user.domain.proto._
import io.circe.{ Decoder, Encoder }
import shapeless.tag
import shapeless.tag.@@

import scala.concurrent.{ ExecutionContext, Future }

object UserEntity {

  implicit val userIdDecoder: Decoder[UserId] = Decoder[String].map(_.asUserId)
  implicit val userIdEncoder: Encoder[UserId] = Encoder.encodeString.contramap(identity)

  implicit class UserIdTaggerOps(v: String) {
    val asUserId: UserId = tag[UserIdTag][String](v)

    def as[U]: String @@ U = tag[U][String](v)
  }

  trait UserIdTag

  type UserId = String @@ UserIdTag

  sealed trait UserCommand[R] extends EntityCommand[UserEntity.UserId, User, R]

  final case class CreateUserCommand(
      entityId: UserId,
      username: String,
      email: String,
      pass: String,
      address: Option[Address] = None,
      department: Option[DepartmentRef] = None
  ) extends UserCommand[CreateUserReply]

  final case class RemoveUserCommand(
      entityId: UserId
  ) extends UserCommand[RemoveUserReply]

  private[domain] final case class CreateUserInternalCommand(
      entityId: UserId,
      username: String,
      email: String,
      pass: String,
      address: Option[Address] = None,
      department: Option[DepartmentRef] = None,
      validation: ValidatedNec[String, Done]
  ) extends UserCommand[CreateUserReply]

  sealed trait CreateOrUpdateUserReply {
    def entityId: UserId
  }

  final case class GetUserCommand(entityId: UserId) extends UserCommand[GetUserReply]

  final case class ChangeUserEmailCommand(entityId: UserId, email: String) extends UserCommand[ChangeUserEmailReply]

  final case class ChangeUserPasswordCommand(entityId: UserId, pass: String) extends UserCommand[ChangeUserPasswordReply]

  final case class ChangeUserAddressCommand(entityId: UserId, address: Option[Address]) extends UserCommand[ChangeUserAddressReply]

  private[domain] final case class ChangeUserAddressInternalCommand(
      entityId: UserId,
      address: Option[Address],
      validation: ValidatedNec[String, Done]
  ) extends UserCommand[ChangeUserAddressReply]

  final case class ChangeUserDepartmentCommand(entityId: UserId, department: Option[DepartmentRef])
      extends UserCommand[ChangeUserDepartmentReply]

  private[domain] final case class ChangeUserDepartmentInternalCommand(
      entityId: UserId,
      department: Option[DepartmentRef],
      validation: ValidatedNec[String, Done]
  ) extends UserCommand[ChangeUserDepartmentReply]

  sealed trait CreateUserReply extends CreateOrUpdateUserReply

  case class UserCreatedReply(entityId: UserId) extends CreateUserReply

  case class UserCreatedFailedReply(entityId: UserId, error: String) extends CreateUserReply

  case class UserAlreadyExistsReply(entityId: UserId) extends CreateUserReply

  sealed trait RemoveUserReply extends CreateOrUpdateUserReply

  case class UserRemovedReply(entityId: UserId) extends RemoveUserReply

  sealed trait GetUserReply

  case class UserReply(user: User) extends GetUserReply

  sealed trait ChangeUserEmailReply extends CreateOrUpdateUserReply

  case class UserEmailChangedReply(entityId: UserId) extends ChangeUserEmailReply

  sealed trait ChangeUserPasswordReply extends CreateOrUpdateUserReply

  case class UserPasswordChangedReply(entityId: UserId) extends ChangeUserPasswordReply

  sealed trait ChangeUserAddressReply extends CreateOrUpdateUserReply

  case class UserAddressChangedReply(entityId: UserId) extends ChangeUserAddressReply

  case class UserAddressChangedFailedReply(entityId: UserId, error: String) extends ChangeUserAddressReply

  sealed trait ChangeUserDepartmentReply extends CreateOrUpdateUserReply

  case class UserDepartmentChangedReply(entityId: UserId) extends ChangeUserDepartmentReply

  case class UserDepartmentChangedFailedReply(entityId: UserId, error: String) extends ChangeUserDepartmentReply

  case class UserNotExistsReply(entityId: UserId)
      extends GetUserReply
      with RemoveUserReply
      with ChangeUserEmailReply
      with ChangeUserPasswordReply
      with ChangeUserAddressReply
      with ChangeUserDepartmentReply

  trait UserEvent extends EntityEvent[UserId]

  implicit val initialEventApplier: InitialEventApplier[User, UserEvent] = {
    case UserPayloadEvent(entityId, _, payload: UserPayloadEvent.Payload.Created, _) =>
      Some(User(entityId, payload.value.username, payload.value.email, payload.value.pass, payload.value.address))
    case _ =>
      //      logError(s"Received user event $otherEvent before actual user booking")
      None
  }

  implicit val eventApplier: EventApplier[User, UserEvent] = (user, event) =>
    event match {
      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.EmailUpdated, _) =>
        val newUser = user.withEmail(payload.value.email)
        Some(newUser)
      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.PasswordUpdated, _) =>
        val newUser = user.withPass(payload.value.pass)
        Some(newUser)
      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.AddressUpdated, _) =>
        val newUser = payload.value.address match {
          case Some(a) => user.withAddress(a)
          case None    => user.clearAddress
        }
        Some(newUser)
      case UserPayloadEvent(_, _, payload: UserPayloadEvent.Payload.DepartmentUpdated, _) =>
        val newUser = payload.value.department match {
          case Some(d) => user.withDepartment(d)
          case None    => user.clearDepartment
        }
        Some(newUser)
      case UserPayloadEvent(_, _, _: UserPayloadEvent.Payload.Removed, _) =>
        None
      case _ =>
        Some(user)
    }

  final val userEventTagger = ShardedEntityEventTagger.sharded[UserEvent](3)

}

sealed class UserPersistentEntity(departmentService: DepartmentService, addressValidationService: AddressValidationService[Future])(
    implicit
    initialApplier: InitialEventApplier[User, UserEntity.UserEvent],
    applier: EventApplier[User, UserEntity.UserEvent]
) extends BasicPersistentEntity[
      UserEntity.UserId,
      User,
      UserEntity.UserCommand,
      UserEntity.UserEvent
    ](UserPersistentEntity.entityName) {

  import scala.concurrent.duration._

  def entityIdFromString(id: String): UserEntity.UserId = {
    import UserEntity._
    id.asUserId
  }

  def entityIdToString(id: UserEntity.UserId): String = id.toString

  val snapshotAdapter: SnapshotAdapter[OuterState] = new SnapshotAdapter[OuterState] {
    override def toJournal(state: OuterState): Any =
      state match {
        case Uninitialized       => UserEntityState(None)
        case Initialized(entity) => UserEntityState(Some(entity))
      }

    override def fromJournal(from: Any): OuterState =
      from match {
        case UserEntityState(Some(entity), _) => Initialized(entity)
        case _                                => Uninitialized
      }
  }

  override def configureEntityBehavior(
      id: UserEntity.UserId,
      behavior: EventSourcedBehavior[Command, UserEntity.UserEvent, OuterState],
      actorContext: ActorContext[Command]
  ): EventSourcedBehavior[Command, UserEntity.UserEvent, OuterState] =
    behavior
      .receiveSignal {
        case (Initialized(state), RecoveryCompleted) =>
          actorContext.log.info(s"Successful recovery of User entity $id in state $state")
        case (Uninitialized, _) =>
          actorContext.log.info(s"User entity $id created in uninitialized state")
        case (state, RecoveryFailed(error)) =>
          actorContext.log.error(s"Failed recovery of User entity $id in state $state: $error")
      }
      .withTagger(UserEntity.userEventTagger.tags)
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

  override protected def commandHandler(
      actorContext: ActorContext[Command]
  ): (OuterState, Command) => ReplyEffect[UserEntity.UserEvent, OuterState] =
    (entityState, command) => {
      entityState match {
        case Uninitialized =>
          commandHandlerUninitialized(actorContext)(command)
        case Initialized(innerState) =>
          commandHandlerInitialized(actorContext)(innerState, command)
      }
    }

  protected def commandHandlerUninitialized(
      actorContext: ActorContext[Command]
  ): Command => ReplyEffect[UserEntity.UserEvent, OuterState] = command => {

    val result = command.command match {
      case UserEntity.CreateUserCommand(entityId, username, email, pass, addr, dep) =>
        implicit val ec = actorContext.executionContext
        BasicPersistentEntity.validated[Command](
          addressValidator(addr) :: departmentValidator(dep) :: Nil,
          v => command.transformUnsafe(UserEntity.CreateUserInternalCommand(entityId, username, email, pass, addr, dep, v))
        )(actorContext)
        CommandProcessResult.withNoReply()
      case UserEntity.CreateUserInternalCommand(entityId, username, email, pass, addr, dep, v) =>
        v match {
          case Validated.Invalid(errors) =>
            CommandProcessResult.withReply(UserEntity.UserCreatedFailedReply(entityId, errors.toList.mkString(", ")))
          case Validated.Valid(_) =>
            val encryptedPass = pass.boundedBcrypt
            val events =
              UserPayloadEvent(
                entityId,
                Instant.now,
                UserPayloadEvent.Payload.Created(UserCreatedPayload(username, email, encryptedPass, addr, dep))
              ) :: Nil
            CommandProcessResult.withReply(events, UserEntity.UserCreatedReply(entityId))
        }
      case otherCommand =>
//        actorContext.log.error(s"Received erroneous initial command $otherCommand for entity")
        CommandProcessResult.withReply(UserEntity.UserNotExistsReply(otherCommand.entityId))
    }

    BasicPersistentEntity.handleProcessResult(result, command.replyTo)
  }

  protected def commandHandlerInitialized(
      actorContext: ActorContext[Command]
  ): (User, Command) => ReplyEffect[UserEntity.UserEvent, OuterState] = (state, command) => {
    val result = command.command match {
      case UserEntity.ChangeUserEmailCommand(entityId, email) =>
        val events =
          UserPayloadEvent(
            entityId,
            Instant.now,
            UserPayloadEvent.Payload.EmailUpdated(UserEmailUpdatedPayload(email))
          ) :: Nil
        CommandProcessResult.withReply(events, UserEntity.UserEmailChangedReply(entityId))
      case UserEntity.ChangeUserPasswordCommand(entityId, pass) =>
        val encryptedPass = pass.boundedBcrypt
        val events =
          UserPayloadEvent(
            entityId,
            Instant.now,
            UserPayloadEvent.Payload.PasswordUpdated(UserPasswordUpdatedPayload(encryptedPass))
          ) :: Nil
        CommandProcessResult.withReply(events, UserEntity.UserPasswordChangedReply(entityId))
      case UserEntity.ChangeUserAddressCommand(entityId, addr) =>
        implicit val ec = actorContext.executionContext
        BasicPersistentEntity.validated[Command](
          addressValidator(addr),
          v => command.transformUnsafe(UserEntity.ChangeUserAddressInternalCommand(entityId, addr, v))
        )(actorContext)
        CommandProcessResult.withNoReply()
      case UserEntity.ChangeUserAddressInternalCommand(entityId, addr, v) =>
        v match {
          case Validated.Invalid(errors) =>
            CommandProcessResult.withReply(UserEntity.UserAddressChangedFailedReply(entityId, errors.toList.mkString(", ")))
          case Validated.Valid(_) =>
            val events =
              UserPayloadEvent(
                entityId,
                Instant.now,
                UserPayloadEvent.Payload.AddressUpdated(UserAddressUpdatedPayload(addr))
              ) :: Nil
            CommandProcessResult.withReply(events, UserEntity.UserAddressChangedReply(entityId))
        }
      case UserEntity.ChangeUserDepartmentCommand(entityId, dep) =>
        implicit val ec = actorContext.executionContext
        BasicPersistentEntity.validated[Command](
          departmentValidator(dep),
          v => command.transformUnsafe(UserEntity.ChangeUserDepartmentInternalCommand(entityId, dep, v))
        )(actorContext)
        CommandProcessResult.withNoReply()
      case UserEntity.ChangeUserDepartmentInternalCommand(entityId, dep, v) =>
        v match {
          case Validated.Invalid(errors) =>
            CommandProcessResult.withReply(UserEntity.UserDepartmentChangedFailedReply(entityId, errors.toList.mkString(", ")))
          case Validated.Valid(_) =>
            val events =
              UserPayloadEvent(
                entityId,
                Instant.now,
                UserPayloadEvent.Payload.DepartmentUpdated(UserDepartmentUpdatedPayload(dep))
              ) :: Nil
            CommandProcessResult.withReply(events, UserEntity.UserDepartmentChangedReply(entityId))
        }
      case UserEntity.RemoveUserCommand(entityId) =>
        val events =
          UserPayloadEvent(
            entityId,
            Instant.now,
            UserPayloadEvent.Payload.Removed(UserRemovedPayload())
          ) :: Nil
        CommandProcessResult.withReply(events, UserEntity.UserRemovedReply(entityId))
      case UserEntity.CreateUserCommand(entityId, _, _, _, _, _) =>
        CommandProcessResult.withReply(UserEntity.UserAlreadyExistsReply(entityId))
      case UserEntity.GetUserCommand(_) =>
        CommandProcessResult.withReply(UserEntity.UserReply(state))
      case otherCommand =>
        //      actorContext.log.error(s"Received erroneous command $otherCommand for entity")
        CommandProcessResult.withReply(UserEntity.UserAlreadyExistsReply(otherCommand.entityId))
    }

    BasicPersistentEntity.handleProcessResult(result, command.replyTo)
  }

  protected def addressValidator(address: Option[Address])(implicit ec: ExecutionContext): () => Future[ValidatedNec[String, Done]] =
    () =>
      address match {
        case Some(address) =>
          addressValidationService.validate(address).map(res => res.map(_ => Done))
        case None => FastFuture.successful(Done.validNec)
      }

  protected def departmentValidator(
      department: Option[DepartmentRef]
  )(implicit ec: ExecutionContext): () => Future[ValidatedNec[String, Done]] =
    () =>
      department match {
        case Some(dep) =>
          departmentService.sendCommand(DepartmentEntity.GetDepartmentCommand(dep.id)).map {
            case DepartmentEntity.DepartmentReply(_)          => Done.validNec
            case _: DepartmentEntity.DepartmentNotExistsReply => s"Department: ${dep.id} not found".invalidNec
          }
        case None => FastFuture.successful(Done.validNec)
      }

  override protected def eventHandler(actorContext: ActorContext[Command]): (OuterState, UserEntity.UserEvent) => OuterState = {
    (entityState, event) =>
      val newEntityState = entityState match {
        case Uninitialized =>
          initialApplier.apply(event)
        case Initialized(state) =>
          applier.apply(state, event)
      }
      newEntityState.map(Initialized).getOrElse[OuterState](Uninitialized)
  }
}

object UserPersistentEntity {
  def apply(departmentService: DepartmentService, addressValidator: AddressValidationService[Future]): UserPersistentEntity =
    new UserPersistentEntity(departmentService, addressValidator)

  val entityName = "user"
}
