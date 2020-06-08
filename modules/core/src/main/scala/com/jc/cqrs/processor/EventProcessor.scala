package com.jc.cqrs.processor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior, PostStop}
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardedDaemonProcessSettings}
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.stream.KillSwitches
import com.jc.cqrs.{EntityEvent, ShardedEntityEventTagger}

import scala.concurrent.duration.FiniteDuration

object EventProcessor {

  def create[E <: EntityEvent[_]](
      name: String,
      eventTagger: ShardedEntityEventTagger[E],
      eventProcessorStream: String => EventProcessorStream[E],
      keepAliveInterval: FiniteDuration
  )(
      implicit system: ActorSystem[_]
  ): Unit = {
    val shardedDaemonSettings = ShardedDaemonProcessSettings(system)
      .withKeepAliveInterval(keepAliveInterval)
      .withShardingSettings(ClusterShardingSettings(system) /*.withRole("read-model")*/ )

    ShardedDaemonProcess(system).init[Nothing](
      s"EventProcessor-${name}",
      eventTagger.numShards,
      i => EventProcessorActor(eventProcessorStream(eventTagger.shardTag(i))),
      shardedDaemonSettings,
      None
    )
  }

}

object EventProcessorActor {

  def apply(eventProcessorStream: EventProcessorStream[_]): Behavior[Nothing] =
    Behaviors.setup[Nothing] { context =>
      val killSwitch = KillSwitches.shared("EventProcessorActorSwitch")

      context.log.debug("starting event processor stream: {}", eventProcessorStream.name)
      eventProcessorStream.runStream(killSwitch)

      Behaviors.receiveSignal[Nothing] {
          case (_, PostStop) =>
            context.log.debug("stopped event processor stream: {}", eventProcessorStream.name)
            killSwitch.shutdown()
            Behaviors.same
        }
    }

}
