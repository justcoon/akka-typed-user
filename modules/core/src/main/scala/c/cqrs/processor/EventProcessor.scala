package c.cqrs.processor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorSystem, Behavior, PostStop }
import akka.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity, EntityTypeKey }
import akka.stream.KillSwitches
import c.cqrs.support.KeepAlive
import c.cqrs.{ EntityEvent, EntityEventTagger }

import scala.concurrent.duration.FiniteDuration

object EventProcessor {

  def create[E](
      name: String,
      shards: Iterable[String],
      eventProcessorStream: String => EventProcessorStream[E],
      keepAliveInterval: FiniteDuration
  )(
      implicit system: ActorSystem[_]
  ): Unit = {
    val eventProcessorEntityKey = EventProcessorActor.entityKey(name)

    ClusterSharding(system).init(
      Entity(eventProcessorEntityKey)(entityContext => EventProcessorActor(eventProcessorStream(entityContext.entityId)))
      /*.withRole("read-model")*/
    )

    KeepAlive.create(name, shards, eventProcessorEntityKey, EventProcessorActor.Ping, keepAliveInterval)
  }

  def create[E <: EntityEvent[_]](
      name: String,
      eventTagger: EntityEventTagger[E],
      eventProcessorStream: String => EventProcessorStream[E],
      keepAliveInterval: FiniteDuration
  )(
      implicit system: ActorSystem[_]
  ): Unit = {
    val shards = eventTagger.allTags

    create(name, shards, eventProcessorStream, keepAliveInterval)
  }

}

object EventProcessorActor {

  case object Ping

  def apply(eventProcessorStream: EventProcessorStream[_]): Behavior[Ping.type] =
    Behaviors.setup { context =>
      val killSwitch = KillSwitches.shared("EventProcessorActorSwitch")

      context.log.debug("starting event processor stream: {}", eventProcessorStream.name)
      eventProcessorStream.runStream(killSwitch)

      Behaviors
        .receiveMessage[Ping.type] { _ =>
          Behaviors.same
        }
        .receiveSignal {
          case (_, PostStop) =>
            context.log.debug("stopped event processor stream: {}", eventProcessorStream.name)
            killSwitch.shutdown()
            Behaviors.same
        }

    }

  def entityKey(eventProcessorName: String): EntityTypeKey[Ping.type] =
    EntityTypeKey[Ping.type](s"EventProcessor-${eventProcessorName}")

}
