package c.user.domain

import java.time.Instant

import akka.actor.typed.ActorRef
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria }
import akka.persistence.typed.{ RecoveryCompleted, RecoveryFailed }
import c.cqrs.BasicPersistentEntity.CommandExpectingReply
import c.cqrs.{
  BasicPersistentEntity,
  CommandProcessResult,
  CommandProcessor,
  CommandReply,
  EntityCommand,
  EntityEvent,
  EventApplier,
  InitialCommandProcessor,
  InitialEventApplier,
  PersistentEntity,
  ShardedEntityEventTagger
}
import io.circe.{ Decoder, Encoder }
import shapeless.tag
import shapeless.tag.@@
import com.github.t3hnar.bcrypt._
import c.user.domain.proto._

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

  private[domain] final case class CreateUserInternalCommand(
      entityId: UserId,
      username: String,
      email: String,
      pass: String,
      address: Option[Address] = None,
      errors: List[String] = Nil
  ) extends UserCommand[CreateUserReply]

  final case class GetUserCommand(entityId: UserId) extends UserCommand[GetUserReply]

  final case class ChangeUserEmailCommand(entityId: UserId, email: String) extends UserCommand[ChangeUserEmailReply]

  final case class ChangeUserPasswordCommand(entityId: UserId, pass: String) extends UserCommand[ChangeUserPasswordReply]

  //  final case class DeleteUserCommand(entityId: UserId) extends Command
  //
  final case class ChangeUserAddressCommand(entityId: UserId, address: Option[Address]) extends UserCommand[ChangeUserAddressReply]

  private[domain] final case class ChangeUserAddressInternalCommand(entityId: UserId, address: Option[Address], errors: List[String] = Nil)
      extends UserCommand[ChangeUserAddressReply]

  sealed trait CreateUserReply

  case class UserCreatedReply(entityId: UserId) extends CreateUserReply

  case class UserCreatedFailedReply(entityId: UserId, error: String) extends CreateUserReply

  case class UserAlreadyExistsReply(entityId: UserId) extends CreateUserReply

  sealed trait GetUserReply

  case class UserReply(user: User) extends GetUserReply

  sealed trait ChangeUserEmailReply

  case class UserEmailChangedReply(entityId: UserId) extends ChangeUserEmailReply

  sealed trait ChangeUserPasswordReply

  case class UserPasswordChangedReply(entityId: UserId) extends ChangeUserPasswordReply

  sealed trait ChangeUserAddressReply

  case class UserAddressChangedReply(entityId: UserId) extends ChangeUserAddressReply

  case class UserAddressChangedFailedReply(entityId: UserId, error: String) extends ChangeUserAddressReply

  case class UserNotExistsReply(entityId: UserId)
      extends GetUserReply
      with ChangeUserEmailReply
      with ChangeUserPasswordReply
      with ChangeUserAddressReply

  trait UserEvent extends EntityEvent[UserId]

  implicit val initialCommandProcessor: InitialCommandProcessor[UserCommand, UserEvent] = {
    case CreateUserCommand(entityId, username, email, pass, address) =>
      val encryptedPass = pass.bcrypt
      val events        = List(UserCreatedEvent(entityId, username, email, encryptedPass, address, Instant.now))
      CommandProcessResult.withReply(events, UserCreatedReply(entityId))
    case otherCommand =>
      //      logError(s"Received erroneous initial command $otherCommand for entity")
      CommandProcessResult.withReply(UserNotExistsReply(otherCommand.entityId))
  }

  implicit val commandProcessor: CommandProcessor[User, UserCommand, UserEvent] =
    (state, command) =>
      command match {
        case CreateUserCommand(entityId, _, _, _, _) =>
          CommandProcessResult.withReply(UserAlreadyExistsReply(entityId))
        case ChangeUserEmailCommand(entityId, email) =>
          val events = List(UserEmailChangedEvent(entityId, email, Instant.now))
          CommandProcessResult.withReply(events, UserEmailChangedReply(entityId))
        case ChangeUserPasswordCommand(entityId, pass) =>
          val encryptedPass = pass.bcrypt
          val events        = List(UserPasswordChangedEvent(entityId, encryptedPass, Instant.now))
          CommandProcessResult.withReply(events, UserEmailChangedReply(entityId))
        case ChangeUserAddressCommand(entityId, addr) =>
          val events = List(UserAddressChangedEvent(entityId, addr, Instant.now))
          CommandProcessResult.withReply(events, UserAddressChangedReply(entityId))
        case GetUserCommand(_) =>
          CommandProcessResult.withReply(UserReply(state))
      }

  implicit val initialEventApplier: InitialEventApplier[User, UserEvent] = {
    case UserCreatedEvent(entityId, username, email, pass, address, _) =>
      Some(User(entityId, username, email, pass, address))
    case _ =>
      //      logError(s"Received user event $otherEvent before actual user booking")
      None
  }

  implicit val eventApplier: EventApplier[User, UserEvent] = (user, event) =>
    event match {
      case UserEmailChangedEvent(_, email, _) =>
        user.copy(email = email)
      case UserPasswordChangedEvent(_, pass, _) =>
        user.copy(pass = pass)
      case UserAddressChangedEvent(_, addr, _) =>
        user.copy(address = addr)
      case _ =>
        user
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

  override def configureEntityBehavior(
      id: UserEntity.UserId,
      behavior: EventSourcedBehavior[Command, UserEntity.UserEvent, OuterState],
      actorContext: ActorContext[Command]
  ): EventSourcedBehavior[Command, UserEntity.UserEvent, OuterState] =
    behavior
      .receiveSignal {
        case (Initialized(state), RecoveryCompleted) =>
          actorContext.log.info(s"Successful recovery of User entity $id in state $state")
        case (Uninitialized(_), _) =>
          actorContext.log.info(s"User entity $id created in uninitialized state")
        case (state, RecoveryFailed(error)) =>
          actorContext.log.error(s"Failed recovery of User entity $id in state $state: $error")
      }
      .withTagger(UserEntity.userEventTagger.tags)
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
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
        case _: Uninitialized =>
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
        actorContext.pipeToSelf(validateAddress(addr)) {
          case Success(_: AddressValidator.ValidResult.type) =>
            transformCommand(command, UserEntity.CreateUserInternalCommand(entityId, username, email, pass, addr))
          case Success(vr: AddressValidator.NotValidResult) =>
            transformCommand(command, UserEntity.CreateUserInternalCommand(entityId, username, email, pass, addr, vr.errors))
          case Failure(exception) =>
            transformCommand(
              command,
              UserEntity.CreateUserInternalCommand(entityId, username, email, pass, addr, exception.getMessage :: Nil)
            )
        }
        CommandProcessResult.withNoReply
      case UserEntity.CreateUserInternalCommand(entityId, username, email, pass, addr, errors) =>
        if (errors.nonEmpty) {
          CommandProcessResult.withReply(UserEntity.UserCreatedFailedReply(entityId, errors.mkString(",")))
        } else {
          val encryptedPass = pass.bcrypt
          val events        = List(UserCreatedEvent(entityId, username, email, encryptedPass, addr, Instant.now))
          CommandProcessResult.withReply(events, UserEntity.UserCreatedReply(entityId))
        }
      case otherCommand =>
        //      logError(s"Received erroneous initial command $otherCommand for entity")
        CommandProcessResult.withReply(UserEntity.UserNotExistsReply(otherCommand.entityId))
    }

    BasicPersistentEntity.handleProcessResult(result, command.replyTo)
  }

  protected def commandHandlerInitialized(
      actorContext: ActorContext[Command]
  ): (User, Command) => ReplyEffect[UserEntity.UserEvent, OuterState] = (state, command) => {
    val result = command.command match {
      case UserEntity.ChangeUserEmailCommand(entityId, email) =>
        val events = List(UserEmailChangedEvent(entityId, email, Instant.now))
        CommandProcessResult.withReply(events, UserEntity.UserEmailChangedReply(entityId))
      case UserEntity.ChangeUserPasswordCommand(entityId, pass) =>
        val encryptedPass = pass.bcrypt
        val events        = List(UserPasswordChangedEvent(entityId, encryptedPass, Instant.now))
        CommandProcessResult.withReply(events, UserEntity.UserEmailChangedReply(entityId))
      case UserEntity.ChangeUserAddressCommand(entityId, addr) =>
        actorContext.pipeToSelf(validateAddress(addr)) {
          case Success(_: AddressValidator.ValidResult.type) =>
            transformCommand(command, UserEntity.ChangeUserAddressInternalCommand(entityId, addr))
          case Success(vr: AddressValidator.NotValidResult) =>
            transformCommand(command, UserEntity.ChangeUserAddressInternalCommand(entityId, addr, vr.errors))
          case Failure(exception) =>
            transformCommand(command, UserEntity.ChangeUserAddressInternalCommand(entityId, addr, exception.getMessage :: Nil))
        }
        CommandProcessResult.withNoReply
      case UserEntity.ChangeUserAddressInternalCommand(entityId, addr, errors) =>
        if (errors.nonEmpty) {
          CommandProcessResult.withReply(UserEntity.UserAddressChangedFailedReply(entityId, errors.mkString(",")))
        } else {
          val events = List(UserAddressChangedEvent(entityId, addr, Instant.now))
          CommandProcessResult.withReply(events, UserEntity.UserAddressChangedReply(entityId))
        }
      case UserEntity.CreateUserCommand(entityId, _, _, _, _) =>
        CommandProcessResult.withReply(UserEntity.UserAlreadyExistsReply(entityId))
      case UserEntity.GetUserCommand(_) =>
        CommandProcessResult.withReply(UserEntity.UserReply(state))
    }

    BasicPersistentEntity.handleProcessResult(result, command.replyTo)
  }

  protected def transformCommand[R](command: Command, newCommand: UserEntity.UserCommand[R]): Command =
    CommandExpectingReply[R, User, UserEntity.UserCommand](newCommand)(command.replyTo.asInstanceOf[ActorRef[R]])

  protected def validateAddress(address: Option[Address]): Future[AddressValidator.ValidationResult] =
    address match {
      case Some(address) => addressValidator.validate(address)
      case None          => Future.successful(AddressValidator.ValidResult)
    }

  override protected def eventHandler(actorContext: ActorContext[Command]): (OuterState, UserEntity.UserEvent) => OuterState = {
    (entityState, event) =>
      entityState match {
        case uninitialized @ Uninitialized(_) =>
          initialApplier.apply(event).map(Initialized).getOrElse[OuterState](uninitialized)
        case Initialized(state) => Initialized(applier.apply(state, event))
      }
  }
}

object UserPersistentEntity {
  def apply(addressValidator: AddressValidator[Future]): UserPersistentEntity = new UserPersistentEntity(addressValidator)

  val entityName = "user"
}
