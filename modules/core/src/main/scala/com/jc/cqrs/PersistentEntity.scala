package com.jc.cqrs

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.cluster.sharding.typed.scaladsl.{ EntityContext, EntityTypeKey }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EffectBuilder, EventSourcedBehavior, ReplyEffect }
import com.jc.cqrs.BasicPersistentEntity.CommandExpectingReply

abstract class BasicPersistentEntity[ID, InnerState, C[R] <: EntityCommand[ID, InnerState, R], E <: EntityEvent[ID]](
    val entityName: String
) {

  sealed trait OuterState

  case class Initialized(state: InnerState) extends OuterState

  case object Uninitialized extends OuterState

  type Command = CommandExpectingReply[_, InnerState, C]

  val entityTypeKey: EntityTypeKey[Command] =
    EntityTypeKey[Command](entityName)

  protected def commandHandler(actorContext: ActorContext[Command]): (OuterState, Command) => ReplyEffect[E, OuterState]

  protected def eventHandler(actorContext: ActorContext[Command]): (OuterState, E) => OuterState

  def entityIdFromString(id: String): ID

  def entityIdToString(id: ID): String

  def eventSourcedEntity(
      entityContext: EntityContext[Command],
      actorContext: ActorContext[Command]
  ): EventSourcedBehavior[Command, E, OuterState] = {
    val id            = entityIdFromString(entityContext.entityId)
    val persistenceId = PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
    configureEntityBehavior(
      id,
      createEventSourcedEntity(id, persistenceId, actorContext),
      actorContext
    )
  }

  protected def configureEntityBehavior(
      id: ID,
      behavior: EventSourcedBehavior[Command, E, OuterState],
      actorContext: ActorContext[Command]
  ): EventSourcedBehavior[Command, E, OuterState]

  private def createEventSourcedEntity(
      id: ID,
      persistenceId: PersistenceId,
      actorContext: ActorContext[Command]
  ) =
    EventSourcedBehavior[Command, E, OuterState](
      persistenceId,
      Uninitialized,
      commandHandler(actorContext),
      eventHandler(actorContext)
    )
}

abstract class PersistentEntity[ID, InnerState, C[R] <: EntityCommand[ID, InnerState, R], E <: EntityEvent[ID]](
    entityName: String
)(
    implicit
    initialProcessor: InitialCommandProcessor[C, E],
    processor: CommandProcessor[InnerState, C, E],
    initialApplier: InitialEventApplier[InnerState, E],
    applier: EventApplier[InnerState, E]
) extends BasicPersistentEntity[ID, InnerState, C, E](entityName) {

  protected def commandHandler(actorContext: ActorContext[Command]): (OuterState, Command) => ReplyEffect[E, OuterState] =
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

  protected def eventHandler(actorContext: ActorContext[Command]): (OuterState, E) => OuterState = { (entityState, event) =>
    entityState match {
      case uninitialized @ Uninitialized =>
        initialApplier.apply(event).map(Initialized).getOrElse[OuterState](uninitialized)
      case Initialized(state) => Initialized(applier.apply(state, event))
    }
  }
}

object BasicPersistentEntity {

  case class CommandExpectingReply[R, InnerState, C[R] <: EntityCommand[_, InnerState, R]](command: C[R])(val replyTo: ActorRef[R])

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

}
