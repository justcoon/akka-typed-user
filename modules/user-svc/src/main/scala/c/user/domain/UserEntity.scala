package c.user.domain

import java.time.Instant

import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.ActorContext
import akka.persistence.typed.scaladsl.{ EventSourcedBehavior, RetentionCriteria }
import akka.persistence.typed.{ RecoveryCompleted, RecoveryFailed }
import c.cqrs.{
  CommandProcessor,
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
  ) extends UserCommand[CreateUserReply] {

    override def initializedReply: User => CreateUserReply = _ => UserAlreadyExistsReply(entityId)

    override def uninitializedReply: CreateUserReply = UserCreatedReply(entityId)
  }

  final case class GetUserCommand(entityId: UserId) extends UserCommand[GetUserReply] {

    override def initializedReply: User => GetUserReply = user => UserReply(user)

    override def uninitializedReply: GetUserReply = UserNotExistsReply(entityId)
  }

  final case class ChangeUserEmailCommand(entityId: UserId, email: String) extends UserCommand[ChangeUserEmailReply] {

    override def initializedReply: User => ChangeUserEmailReply =
      _ => UserEmailChangedReply(entityId)

    override def uninitializedReply: ChangeUserEmailReply = UserNotExistsReply(entityId)
  }

  final case class ChangeUserPasswordCommand(entityId: UserId, pass: String) extends UserCommand[ChangeUserPasswordReply] {

    override def initializedReply: User => ChangeUserPasswordReply =
      _ => UserPasswordChangedReply(entityId)

    override def uninitializedReply: ChangeUserPasswordReply = UserNotExistsReply(entityId)
  }

  //  final case class DeleteUserCommand(entityId: UserId) extends Command
  //
  final case class ChangeUserAddressCommand(entityId: UserId, address: Option[Address]) extends UserCommand[ChangeUserAddressReply] {

    override def initializedReply: User => ChangeUserAddressReply =
      _ => UserAddressChangedReply(entityId)

    override def uninitializedReply: ChangeUserAddressReply = UserNotExistsReply(entityId)
  }

  sealed trait CreateUserReply

  case class UserCreatedReply(entityId: UserId) extends CreateUserReply

  case class UserAlreadyExistsReply(entityId: UserId) extends CreateUserReply

  sealed trait GetUserReply

  case class UserReply(user: User) extends GetUserReply

  sealed trait ChangeUserEmailReply

  case class UserEmailChangedReply(entityId: UserId) extends ChangeUserEmailReply

  sealed trait ChangeUserPasswordReply

  case class UserPasswordChangedReply(entityId: UserId) extends ChangeUserPasswordReply

  sealed trait ChangeUserAddressReply

  case class UserAddressChangedReply(entityId: UserId) extends ChangeUserAddressReply

  case class UserNotExistsReply(entityId: UserId)
      extends GetUserReply
      with ChangeUserEmailReply
      with ChangeUserPasswordReply
      with ChangeUserAddressReply

  trait UserEvent extends EntityEvent[UserId]

  implicit val initialCommandProcessor: InitialCommandProcessor[UserCommand, UserEvent] = {
    case CreateUserCommand(entityId, username, email, pass, address) =>
      val encryptedPass = pass.bcrypt
      List(UserCreatedEvent(entityId, username, email, encryptedPass, address, Instant.now))
    case _ =>
      //      logError(s"Received erroneous initial command $otherCommand for entity")
      Nil
  }

  implicit val commandProcessor: CommandProcessor[User, UserCommand, UserEvent] =
    (state, command) =>
      command match {
        case ChangeUserEmailCommand(entityId, email) =>
          List(UserEmailChangedEvent(entityId, email, Instant.now))
        case ChangeUserPasswordCommand(entityId, pass) =>
          val encryptedPass = pass.bcrypt
          List(UserPasswordChangedEvent(entityId, encryptedPass, Instant.now))
        case ChangeUserAddressCommand(entityId, addr) =>
          List(UserAddressChangedEvent(entityId, addr, Instant.now))
        case GetUserCommand(_) =>
          Nil
        case _ =>
          Nil
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

sealed class UserPersistentEntity()
    extends PersistentEntity[
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
}

object UserPersistentEntity {
  def apply(): UserPersistentEntity = new UserPersistentEntity

  val entityName = "user"
}
