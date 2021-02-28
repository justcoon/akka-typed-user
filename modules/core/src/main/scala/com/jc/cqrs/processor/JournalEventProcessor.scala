package com.jc.cqrs.processor

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.cluster.sharding.typed.{ ClusterShardingSettings, ShardedDaemonProcessSettings }
import akka.projection.ProjectionBehavior
import akka.projection.eventsourced.EventEnvelope
import akka.projection.scaladsl.AtLeastOnceFlowProjection
import akka.stream.Materializer
import com.jc.cqrs.{ EntityEvent, ShardedEntityEventTagger }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

object JournalEventProcessor {

  def create[E <: EntityEvent[_], O](
      name: String,
      eventTagger: ShardedEntityEventTagger[E],
      projection: String => AtLeastOnceFlowProjection[O, EventEnvelope[E]],
      keepAliveInterval: FiniteDuration
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit = {

    val shardedDaemonSettings = ShardedDaemonProcessSettings(system)
      .withKeepAliveInterval(keepAliveInterval)
      .withShardingSettings(ClusterShardingSettings(system) /*.withRole("read-model")*/ )

    //#running-with-daemon-process
    ShardedDaemonProcess(system).init[ProjectionBehavior.Command](
      name = s"EventProcessor-${name}",
      numberOfInstances = eventTagger.numShards,
      behaviorFactory = (n: Int) => ProjectionBehavior(projection(eventTagger.shardTag(n))),
      settings = shardedDaemonSettings,
      stopMessage = Some(ProjectionBehavior.Stop)
    )
    //#running-with-daemon-process
  }

}
