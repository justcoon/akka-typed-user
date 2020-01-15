package c.user.domain

import java.time.Instant

import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import akka.persistence.typed.{RecoveryCompleted, RecoveryFailed}
import c.cqrs.{CommandProcessor, EntityCommand, EntityEvent, EventApplier, InitialCommandProcessor, InitialEventApplier, PersistentEntity}
import io.circe.{Decoder, Encoder}
import shapeless.tag
import shapeless.tag.@@

object UserEntity {

  implicit val userIdDecoder: Decoder[UserId] = Decoder[String].map(_.asUserId)
  implicit val userIdEncoder: Encoder[UserId] = Encoder.encodeString.contramap(identity)

  implicit class UserIdTaggerOps(v: String) {
    val asUserId: UserId = tag[UserIdTag][String](v)

    def as[U]: String @@ U = tag[U][String](v)
  }

  trait UserIdTag

  type UserId = String @@ UserIdTag

  case class Address(
      street: String,
      number: String,
      zip: String,
      city: String,
      state: String,
      country: String
  )

  case class User(
      id: UserId,
      username: String,
      email: String,
      pass: String = "",
      address: Option[Address] = None
  )

  sealed trait UserCommand[R] extends EntityCommand[UserEntity.UserId, User, R]

  final case class CreateUserCommand(
      entityID: UserId,
      username: String,
      email: String,
      pass: String,
      address: Option[Address] = None
  ) extends UserCommand[CreateUserReply] {

    override def initializedReply: User => CreateUserReply = _ => UserAlreadyExistsReply(entityID)

    override def uninitializedReply: CreateUserReply = UserCreatedReply(entityID)
  }

  final case class GetUserCommand(entityID: UserId) extends UserCommand[GetUserReply] {

    override def initializedReply: User => GetUserReply = user => UserReply(user)

    override def uninitializedReply: GetUserReply = UserNotExistsReply(entityID)
  }

  final case class ChangeUserEmailCommand(entityID: UserId, email: String) extends UserCommand[ChangeUserEmailReply] {

    override def initializedReply: User => ChangeUserEmailReply =
      _ => UserEmailChangedReply(entityID)

    override def uninitializedReply: ChangeUserEmailReply = UserNotExistsReply(entityID)
  }

  final case class ChangeUserPasswordCommand(entityID: UserId, pass: String) extends UserCommand[ChangeUserPasswordReply] {

    override def initializedReply: User => ChangeUserPasswordReply =
      _ => UserPasswordChangedReply(entityID)

    override def uninitializedReply: ChangeUserPasswordReply = UserNotExistsReply(entityID)
  }

  //  final case class DeleteUserCommand(entityID: UserId) extends Command
  //
  final case class ChangeUserAddressCommand(entityID: UserId, address: Option[Address]) extends UserCommand[ChangeUserAddressReply] {

    override def initializedReply: User => ChangeUserAddressReply =
      _ => UserAddressChangedReply(entityID)

    override def uninitializedReply: ChangeUserAddressReply = UserNotExistsReply(entityID)
  }

  sealed trait CreateUserReply

  case class UserCreatedReply(entityID: UserId) extends CreateUserReply

  case class UserAlreadyExistsReply(entityID: UserId) extends CreateUserReply

  sealed trait GetUserReply

  case class UserReply(user: User) extends GetUserReply

  sealed trait ChangeUserEmailReply

  case class UserEmailChangedReply(entityID: UserId) extends ChangeUserEmailReply

  sealed trait ChangeUserPasswordReply

  case class UserPasswordChangedReply(entityID: UserId) extends ChangeUserPasswordReply

  sealed trait ChangeUserAddressReply

  case class UserAddressChangedReply(entityID: UserId) extends ChangeUserAddressReply

  case class UserNotExistsReply(entityID: UserId)
      extends GetUserReply
      with ChangeUserEmailReply
      with ChangeUserPasswordReply
      with ChangeUserAddressReply

  sealed trait UserEvent extends EntityEvent[UserId]

  case class UserCreatedEvent(
      entityID: UserId,
      username: String,
      email: String,
      pass: String,
      address: Option[Address],
      timestamp: Instant = Instant.now
  ) extends UserEvent

  case class UserPasswordChangedEvent(
      entityID: UserId,
      pass: String,
      timestamp: Instant = Instant.now
  ) extends UserEvent

  case class UserEmailChangedEvent(
      entityID: UserId,
      email: String,
      timestamp: Instant = Instant.now
  ) extends UserEvent

  case class UserAddressChangedEvent(
      entityID: UserId,
      address: Option[Address],
      timestamp: Instant = Instant.now
  ) extends UserEvent

  case class UserRemovedEvent(entityID: UserId, timestamp: Instant = Instant.now) extends UserEvent

  implicit val initialCommandProcessor: InitialCommandProcessor[UserCommand, UserEvent] = {
    case CreateUserCommand(entityID, username, email, pass, address) =>
      List(UserCreatedEvent(entityID, username, email, pass, address))
    case _ =>
      //      logError(s"Received erroneous initial command $otherCommand for entity")
      Nil
  }

  implicit val commandProcessor: CommandProcessor[User, UserCommand, UserEvent] =
    (state, command) =>
      command match {
        case ChangeUserEmailCommand(entityID, email) =>
          List(UserEmailChangedEvent(entityID, email))
        case ChangeUserPasswordCommand(entityID, pass) =>
          List(UserPasswordChangedEvent(entityID, pass))
        case ChangeUserAddressCommand(entityID, addr) =>
          List(UserAddressChangedEvent(entityID, addr))
        case GetUserCommand(_) =>
          Nil
        case _ =>
          Nil
      }

  implicit val initialEventApplier: InitialEventApplier[User, UserEvent] = {
    case UserCreatedEvent(entityID, username, email, pass, address, _) =>
      Some(User(entityID, username, email, pass, address))
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

  //  final val EventTagger = ShardedEntityEventTagger.sharded[UserEvent](3)

  val userEventTagger: UserEvent => Set[String] = { event =>
    //            val tags = EventTagger.tags(event)
    val tags = Set(UserPersistentEntity.entityName)
    tags
  }

}

sealed class UserPersistentEntity()
    extends PersistentEntity[
      UserEntity.UserId,
      UserEntity.User,
      UserEntity.UserCommand,
      UserEntity.UserEvent
    ](UserPersistentEntity.entityName) {

  import scala.concurrent.duration._

  def entityIDFromString(id: String): UserEntity.UserId = {
    import UserEntity._
    id.asUserId
  }

  def entityIDToString(id: UserEntity.UserId): String = id.toString

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
      .withTagger(UserEntity.userEventTagger)
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
}

object UserPersistentEntity {
  def apply(): UserPersistentEntity = new UserPersistentEntity

  val entityName = "user"
}
