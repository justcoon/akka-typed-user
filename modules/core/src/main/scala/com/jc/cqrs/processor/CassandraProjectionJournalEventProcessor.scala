package com.jc.cqrs.processor

import akka.Done
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.cluster.sharding.typed.{ ClusterShardingSettings, ShardedDaemonProcessSettings }
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.projection.cassandra.scaladsl.CassandraProjection
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.{ ProjectionBehavior, ProjectionContext, ProjectionId }
import akka.stream.Materializer
import akka.stream.scaladsl.FlowWithContext
import com.jc.cqrs.{ EntityEvent, ShardedEntityEventTagger }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ FiniteDuration, _ }

//https://github.com/akka/akka-projection/blob/master/examples/src/it/scala/docs/cassandra/CassandraProjectionDocExample.scala
object CassandraProjectionJournalEventProcessor {
  val keepAliveDefault: FiniteDuration = 3.seconds

  def create[E <: EntityEvent[_]](
      name: String,
      eventTagger: ShardedEntityEventTagger[E],
      handleEvent: FlowWithContext[EventEnvelope[E], ProjectionContext, Done, ProjectionContext, _],
      keepAliveInterval: FiniteDuration = keepAliveDefault
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit =
    create(name, name, eventTagger, handleEvent, keepAliveInterval)

  def create[E <: EntityEvent[_]](
      name: String,
      offsetNamePrefix: String,
      eventTagger: ShardedEntityEventTagger[E],
      handleEvent: FlowWithContext[EventEnvelope[E], ProjectionContext, Done, ProjectionContext, _],
      keepAliveInterval: FiniteDuration
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit = {

    val sourceProvider = (shardTag: String) =>
      EventSourcedProvider
        .eventsByTag[E](system = system, readJournalPluginId = CassandraReadJournal.Identifier, tag = shardTag)

    val projection = (shardTag: String) =>
      CassandraProjection.atLeastOnceFlow(projectionId = ProjectionId(offsetNamePrefix, shardTag), sourceProvider(shardTag), handleEvent)

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
