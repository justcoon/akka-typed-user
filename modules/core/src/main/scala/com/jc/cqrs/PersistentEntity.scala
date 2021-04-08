package com.jc.cqrs

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.cluster.sharding.typed.scaladsl.{ EntityContext, EntityTypeKey }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EffectBuilder, EventSourcedBehavior, ReplyEffect }
import cats.data.ValidatedNec
import cats.kernel.Monoid
import cats.implicits._
import com.jc.cqrs.BasicPersistentEntity.CommandExpectingReply

import scala.concurrent.Future
import scala.util.{ Failure, Success }

abstract class BasicPersistentEntity[ID, S, C[R] <: EntityCommand[ID, S, R], E <: EntityEvent[ID]](
    val entityName: String
) {

  sealed trait EntityState

  case class Initialized(state: S) extends EntityState

  case object Uninitialized extends EntityState

  type Command = CommandExpectingReply[_, S, C]

  val entityTypeKey: EntityTypeKey[Command] = EntityTypeKey[Command](entityName)

  protected def commandHandler(actorContext: ActorContext[Command]): (EntityState, Command) => ReplyEffect[E, EntityState]

  protected def eventHandler(actorContext: ActorContext[Command]): (EntityState, E) => EntityState

  def entityIdFromString(id: String): ID

  def entityIdToString(id: ID): String

  final def eventSourcedEntity(
      entityContext: EntityContext[Command],
      actorContext: ActorContext[Command]
  ): EventSourcedBehavior[Command, E, EntityState] = {
    val id            = entityIdFromString(entityContext.entityId)
    val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
    configureEntityBehavior(
      id,
      createEventSourcedEntity(persistenceId, actorContext),
      actorContext
    )
  }

  protected def configureEntityBehavior(
      id: ID,
      behavior: EventSourcedBehavior[Command, E, EntityState],
      actorContext: ActorContext[Command]
  ): EventSourcedBehavior[Command, E, EntityState]

  final private def createEventSourcedEntity(
      persistenceId: PersistenceId,
      actorContext: ActorContext[Command]
  ) =
    EventSourcedBehavior[Command, E, EntityState](
      persistenceId,
      Uninitialized,
      commandHandler(actorContext),
      eventHandler(actorContext)
    )
}

abstract class PersistentEntity[ID, S, C[R] <: EntityCommand[ID, S, R], E <: EntityEvent[ID]](
    entityName: String
)(implicit
    initialProcessor: InitialCommandProcessor[C, E],
    processor: CommandProcessor[S, C, E],
    initialApplier: InitialEventApplier[S, E],
    applier: EventApplier[S, E]
) extends BasicPersistentEntity[ID, S, C, E](entityName) {

  protected def commandHandler(actorContext: ActorContext[Command]): (EntityState, Command) => ReplyEffect[E, EntityState] =
    (entityState, command) => {
      entityState match {
        case Uninitialized =>
          val result = initialProcessor.process(command.command)
          BasicPersistentEntity.handleProcessResult(result, command.replyTo)
        case Initialized(innerState) =>
          val result = processor.process(innerState, command.command)
          BasicPersistentEntity.handleProcessResult(result, command.replyTo)
      }
    }

  protected def eventHandler(actorContext: ActorContext[Command]): (EntityState, E) => EntityState = { (entityState, event) =>
    val newEntityState = entityState match {
      case Uninitialized =>
        initialApplier.apply(event)
      case Initialized(state) =>
        applier.apply(state, event)
    }
    newEntityState.map(Initialized).getOrElse[EntityState](Uninitialized)
  }
}

object BasicPersistentEntity {

  final case class CommandExpectingReply[R, S, C[R] <: EntityCommand[_, S, R]](command: C[R])(val replyTo: ActorRef[R]) {
    def transform[NC[R] <: EntityCommand[_, S, R]](newCommand: NC[R]): CommandExpectingReply[R, S, NC] =
      CommandExpectingReply[R, S, NC](newCommand)(replyTo)

    def transformUnsafe[NR, NC[NR] <: EntityCommand[_, S, NR]](newCommand: NC[NR]): CommandExpectingReply[NR, S, NC] =
      CommandExpectingReply[NR, S, NC](newCommand)(replyTo.asInstanceOf[ActorRef[NR]])
  }

  def handleProcessResult[R, E <: EntityEvent[_], S](
      result: CommandProcessResult[E],
      replyTo: ActorRef[R]
  ): ReplyEffect[E, S] = {
    val effect: EffectBuilder[E, S] = if (result.events.nonEmpty) Effect.persist(result.events) else Effect.none
    result.reply match {
      case commandReply: CommandReply.Reply[R] =>
        effect.thenReply(replyTo)(_ => commandReply.reply)
      case CommandReply.NoReply =>
        effect.thenNoReply()
    }
  }

  def errorMessageToValidated(error: Throwable): ValidatedNec[String, Done] =
    error.getMessage.invalidNec

  def validated[C, E](
      validator: () => Future[ValidatedNec[E, Done]],
      validatedToCommand: ValidatedNec[E, Done] => C,
      errorToValidated: Throwable => ValidatedNec[E, Done]
  )(
      actorContext: ActorContext[C]
  ): Unit =
    actorContext.pipeToSelf(validator()) {
      case Success(v) =>
        validatedToCommand(v)
      case Failure(error) =>
        validatedToCommand(errorToValidated(error))
    }

  def validated[C, E](
      validators: Seq[() => Future[ValidatedNec[E, Done]]],
      validatedToCommand: ValidatedNec[E, Done] => C,
      errorToValidated: Throwable => ValidatedNec[E, Done]
  )(
      actorContext: ActorContext[C]
  ): Unit = {
    implicit val ec = actorContext.executionContext

    implicit val doneMonoid = Monoid.instance[Done](Done, (_, _) => Done)

    val validator = () => Future.traverse(validators)(_()).map(_.combineAll)

    validated(validator, validatedToCommand, errorToValidated)(actorContext)
  }

}
