package com.jc.user.domain

import java.time.Instant

import akka.actor.typed.{ ActorRef, SupervisorStrategy }
import akka.actor.typed.scaladsl.ActorContext
import akka.http.scaladsl.util.FastFuture
import akka.persistence.typed.scaladsl.{ EventSourcedBehavior, ReplyEffect, RetentionCriteria }
import akka.persistence.typed.{ RecoveryCompleted, RecoveryFailed, SnapshotAdapter }
import com.github.t3hnar.bcrypt._
import com.jc.cqrs.BasicPersistentEntity.CommandExpectingReply
import com.jc.cqrs._
import com.jc.user.domain.proto._
import io.circe.{ Decoder, Encoder }
import shapeless.tag
import shapeless.tag.@@

import scala.concurrent.Future
import scala.util.{ Failure, Success }

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
      address: Option[Address] = None
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
      errors: List[String] = Nil
  ) extends UserCommand[CreateUserReply]

  sealed trait CreateOrUpdateUserReply {
    def entityId: UserId
  }

  final case class GetUserCommand(entityId: UserId) extends UserCommand[GetUserReply]

  final case class ChangeUserEmailCommand(entityId: UserId, email: String) extends UserCommand[ChangeUserEmailReply]

  final case class ChangeUserPasswordCommand(entityId: UserId, pass: String) extends UserCommand[ChangeUserPasswordReply]

  final case class ChangeUserAddressCommand(entityId: UserId, address: Option[Address]) extends UserCommand[ChangeUserAddressReply]

  private[domain] final case class ChangeUserAddressInternalCommand(entityId: UserId, address: Option[Address], errors: List[String] = Nil)
      extends UserCommand[ChangeUserAddressReply]

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

  case class UserNotExistsReply(entityId: UserId)
      extends GetUserReply
      with RemoveUserReply
      with ChangeUserEmailReply
      with ChangeUserPasswordReply
      with ChangeUserAddressReply

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
      case UserPayloadEvent(_, _, _: UserPayloadEvent.Payload.Removed, _) =>
        None
      case _ =>
        Some(user)
    }

  final val userEventTagger = ShardedEntityEventTagger.sharded[UserEvent](3)

}

sealed class UserPersistentEntity(addressValidator: AddressValidator[Future])(
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
      case UserEntity.CreateUserCommand(entityId, username, email, pass, addr) =>
        validateAddress(
          addr,
          command.transformUnsafe(UserEntity.CreateUserInternalCommand(entityId, username, email, pass, addr)),
          errors => command.transformUnsafe(UserEntity.CreateUserInternalCommand(entityId, username, email, pass, addr, errors))
        )(actorContext)
        CommandProcessResult.withNoReply()
      case UserEntity.CreateUserInternalCommand(entityId, username, email, pass, addr, errors) =>
        if (errors.nonEmpty) {
          CommandProcessResult.withReply(UserEntity.UserCreatedFailedReply(entityId, errors.mkString(",")))
        } else {
          val encryptedPass = pass.boundedBcrypt
          val events =
            UserPayloadEvent(
              entityId,
              Instant.now,
              UserPayloadEvent.Payload.Created(UserCreatedPayload(username, email, encryptedPass, addr))
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
        validateAddress(
          addr,
          command.transformUnsafe(UserEntity.ChangeUserAddressInternalCommand(entityId, addr)),
          errors => command.transformUnsafe(UserEntity.ChangeUserAddressInternalCommand(entityId, addr, errors))
        )(actorContext)
        CommandProcessResult.withNoReply()
      case UserEntity.ChangeUserAddressInternalCommand(entityId, addr, errors) =>
        if (errors.nonEmpty) {
          CommandProcessResult.withReply(UserEntity.UserAddressChangedFailedReply(entityId, errors.mkString(",")))
        } else {
          val events =
            UserPayloadEvent(
              entityId,
              Instant.now,
              UserPayloadEvent.Payload.AddressUpdated(UserAddressUpdatedPayload(addr))
            ) :: Nil
          CommandProcessResult.withReply(events, UserEntity.UserAddressChangedReply(entityId))
        }
      case UserEntity.RemoveUserCommand(entityId) =>
        val events =
          UserPayloadEvent(
            entityId,
            Instant.now,
            UserPayloadEvent.Payload.Removed(UserRemovedPayload())
          ) :: Nil
        CommandProcessResult.withReply(events, UserEntity.UserRemovedReply(entityId))
      case UserEntity.CreateUserCommand(entityId, _, _, _, _) =>
        CommandProcessResult.withReply(UserEntity.UserAlreadyExistsReply(entityId))
      case UserEntity.GetUserCommand(_) =>
        CommandProcessResult.withReply(UserEntity.UserReply(state))
      case otherCommand =>
        //      actorContext.log.error(s"Received erroneous command $otherCommand for entity")
        CommandProcessResult.withReply(UserEntity.UserAlreadyExistsReply(otherCommand.entityId))
    }

    BasicPersistentEntity.handleProcessResult(result, command.replyTo)
  }

  protected def validateAddress(address: Option[Address], onSuccess: => Command, onError: List[String] => Command)(
      actorContext: ActorContext[Command]
  ): Unit = {

    val addressValidationF = address match {
      case Some(address) => addressValidator.validate(address)
      case None          => FastFuture.successful(AddressValidator.ValidResult)
    }

    actorContext.pipeToSelf(addressValidationF) {
      case Success(_: AddressValidator.ValidResult.type) =>
        onSuccess
      case Success(vr: AddressValidator.NotValidResult) =>
        onError(vr.errors)
      case Failure(exception) =>
        onError(exception.getMessage :: Nil)
    }
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
  def apply(addressValidator: AddressValidator[Future]): UserPersistentEntity = new UserPersistentEntity(addressValidator)

  val entityName = "user"
}
