package com.jc.cqrs.processor

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.persistence.query
import akka.persistence.query.{ Offset, PersistenceQuery, Sequence }
import akka.persistence.query.scaladsl.{ CurrentEventsByPersistenceIdQuery, EventsByPersistenceIdQuery }
import akka.projection.eventsourced.EventEnvelope
import akka.projection.scaladsl.SourceProvider
import akka.stream.scaladsl.Source

import scala.concurrent.{ ExecutionContext, Future }

object PersistenceQuerySourceProvider {

  def eventsByPersistenceId[Event](
      system: ActorSystem[_],
      readJournalPluginId: String,
      id: String
  ): SourceProvider[Offset, EventEnvelope[Event]] = {

    val persistenceIdQuery =
      PersistenceQuery(system).readJournalFor[EventsByPersistenceIdQuery](readJournalPluginId)

    new EventsByPersistenceIdSourceProvider(persistenceIdQuery.eventsByPersistenceId, id, system)
  }

  def currentEventsByPersistenceId[Event](
      system: ActorSystem[_],
      readJournalPluginId: String,
      id: String
  ): SourceProvider[Offset, EventEnvelope[Event]] = {

    val persistenceIdQuery = PersistenceQuery(system).readJournalFor[CurrentEventsByPersistenceIdQuery](readJournalPluginId)

    new EventsByPersistenceIdSourceProvider(persistenceIdQuery.currentEventsByPersistenceId, id, system)
  }

  private class EventsByPersistenceIdSourceProvider[Event](
      persistenceIdQuery: (String, Long, Long) => Source[query.EventEnvelope, NotUsed],
      id: String,
      system: ActorSystem[_]
  ) extends SourceProvider[Offset, EventEnvelope[Event]] {
    implicit val executionContext: ExecutionContext = system.executionContext

    override def source(offset: () => Future[Option[Offset]]): Future[Source[EventEnvelope[Event], NotUsed]] =
      offset().map { offsetOpt =>
        val startSequenceNr = offsetOpt match {
          case Some(Sequence(n)) => n
          case _                 => 0L
        }
        persistenceIdQuery(id, startSequenceNr, Long.MaxValue)
          .map(env => EventEnvelope(env.offset, env.persistenceId, env.sequenceNr, env.event.asInstanceOf[Event], env.timestamp))
      }

    override def extractOffset(envelope: EventEnvelope[Event]): Offset = Offset.sequence(envelope.sequenceNr)

    override def extractCreationTime(envelope: EventEnvelope[Event]): Long = envelope.timestamp
  }
}
