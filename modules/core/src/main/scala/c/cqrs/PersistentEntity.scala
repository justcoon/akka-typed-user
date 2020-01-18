package c.cqrs

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.ActorContext
import akka.cluster.sharding.typed.scaladsl.{EntityContext, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EffectBuilder, EventSourcedBehavior, ReplyEffect}
import c.cqrs.PersistentEntity.CommandExpectingReply

abstract class PersistentEntity[ID, InnerState, C[R] <: EntityCommand[ID, InnerState, R], E <: EntityEvent[ID]](
    val entityName: String
)(
    implicit
    initialProcessor: InitialCommandProcessor[C, E],
    processor: CommandProcessor[InnerState, C, E],
    initialApplier: InitialEventApplier[InnerState, E],
    applier: EventApplier[InnerState, E]
) {

  sealed trait OuterState

  case class Initialized(state: InnerState) extends OuterState

  case class Uninitialized(id: ID) extends OuterState

  type Command = CommandExpectingReply[_, InnerState, C]

  val entityTypeKey: EntityTypeKey[Command] =
    EntityTypeKey[Command](entityName)

  private val commandHandler: (OuterState, Command) => ReplyEffect[E, OuterState] =
    (entityState, command) => {
      entityState match {
        case _: Uninitialized =>
          val events = initialProcessor.process(command.command)
          command.uninitializedReplyAfter(
            if (events.nonEmpty) Effect.persist(events) else Effect.none
          )
        case Initialized(innerState) =>
          val events = processor.process(innerState, command.command)
          command.initializedReplyAfter(
            if (events.nonEmpty) Effect.persist(events) else Effect.none,
            innerState
          )
      }
    }

  private val eventHandler: (OuterState, E) => OuterState = { (entityState, event) =>
    entityState match {
      case uninitialized @ Uninitialized(_) =>
        initialApplier.apply(event).map(Initialized).getOrElse[OuterState](uninitialized)
      case Initialized(state) => Initialized(applier.apply(state, event))
    }
  }

  def entityIDFromString(id: String): ID

  def entityIDToString(id: ID): String

  def eventSourcedEntity(
      entityContext: EntityContext[Command],
      actorContext: ActorContext[Command]
  ): EventSourcedBehavior[Command, E, OuterState] = {
    val id            = entityIDFromString(entityContext.entityId)
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
      Uninitialized(id),
      commandHandler,
      eventHandler
    )
}

object PersistentEntity {

  case class CommandExpectingReply[R, InnerState, C[Reply] <: EntityCommand[_, InnerState, Reply]](
      command: C[R]
  )(
      val replyTo: ActorRef[R]
  ) /*extends ExpectingReply[R]*/ {
    def initializedReplyAfter[E, S](
        effect: EffectBuilder[E, S],
        state: InnerState
    ): ReplyEffect[E, S] =
      effect.thenReply(replyTo)(_ => command.initializedReply(state))

    def uninitializedReplyAfter[E, S](effect: EffectBuilder[E, S]): ReplyEffect[E, S] =
      effect.thenReply(replyTo)(_ => command.uninitializedReply)
  }

}
