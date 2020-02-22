package com.jc.cqrs.processor

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.{ EventEnvelope, Offset, PersistenceQuery }
import akka.stream.Materializer
import akka.stream.scaladsl.{ FlowWithContext, SourceWithContext }
import com.jc.cqrs.{ EntityEvent, EntityEventTagger }
import com.jc.cqrs.offsetstore.OffsetStore

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.{ FiniteDuration, _ }

object CassandraJournalEventProcessor {
  val keepAliveDefault: FiniteDuration = 3.seconds

  def create[E <: EntityEvent[_]](
      name: String,
      eventTagger: EntityEventTagger[E],
      handleEvent: FlowWithContext[E, Offset, _, Offset, NotUsed],
      offsetStore: OffsetStore[Offset, Future],
      keepAliveInterval: FiniteDuration = keepAliveDefault
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit =
    create(name, name, eventTagger, handleEvent, offsetStore, keepAliveInterval)

  def create[E <: EntityEvent[_]](
      name: String,
      offsetNamePrefix: String,
      eventTagger: EntityEventTagger[E],
      handleEvent: FlowWithContext[E, Offset, _, Offset, NotUsed],
      offsetStore: OffsetStore[Offset, Future],
      keepAliveInterval: FiniteDuration
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit = {

    val readJournal = PersistenceQuery(system).readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

    val offsetName    = (shardId: String) => s"$offsetNamePrefix-$shardId"
    val initialOffset = (storedOffset: Option[Offset]) => storedOffset.getOrElse(Offset.timeBasedUUID(readJournal.firstOffset))

    val eventStreamFactory = (shardId: String, initialOffset: Offset) =>
      SourceWithContext.fromTuples {
        readJournal
          .eventsByTag(shardId, initialOffset)
          .collect {
            case EventEnvelope(offset, _, _, event: E) => (event, offset)
          }
      }

    val eventProcessorStream: String => EventProcessorStream[E] = shardId =>
      EventProcessorStream.create(
        shardId,
        offsetName,
        initialOffset,
        offsetStore,
        eventStreamFactory,
        handleEvent
      )

    EventProcessor.create(name, eventTagger, eventProcessorStream, keepAliveInterval)
  }

}
