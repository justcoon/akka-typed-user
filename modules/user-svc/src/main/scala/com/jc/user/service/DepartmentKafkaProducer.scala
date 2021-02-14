package com.jc.user.service

import akka.actor.typed.ActorSystem
import akka.kafka.scaladsl.{ DiscoverySupport, Producer }
import akka.kafka.{ ProducerMessage, ProducerSettings }
import akka.persistence.query.Offset
import akka.projection.ProjectionContext
import akka.projection.eventsourced.EventEnvelope
import akka.stream.Materializer
import akka.stream.scaladsl.FlowWithContext
import akka.{ Done, NotUsed }
import com.jc.cqrs.offsetstore.OffsetStore
import com.jc.cqrs.processor.{ CassandraJournalEventProcessor, CassandraProjectionJournalEventProcessor }
import com.jc.user.domain.{ DepartmentAggregate, DepartmentEntity }
import com.jc.user.domain.proto.DepartmentPayloadEvent
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{ Serializer, StringSerializer }

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.concurrent.{ ExecutionContext, Future }

object DepartmentKafkaProducer {
  val DepartmentKafkaOffsetNamePrefix = "departmentKafka"

  val DepartmentKafkaProducerName = "departmentKafkaProducer"

  val keepAlive: FiniteDuration = 3.seconds

  private def createKafkaProducerSettings()(implicit system: ActorSystem[_]): ProducerSettings[String, DepartmentEntity.DepartmentEvent] = {
    import akka.actor.typed.scaladsl.adapter._

    val producerConfig = system.settings.config.getConfig("akka.kafka.producer")
    ProducerSettings(system.toClassic, new StringSerializer, departmentEventProtoKafkaSerializer)
      .withEnrichAsync(DiscoverySupport.producerBootstrapServers(producerConfig))
  }

  def create(
      kafkaTopic: String,
      offsetStore: OffsetStore[Offset, Future]
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit = {

    val settings = createKafkaProducerSettings()

    val handleEvent: FlowWithContext[DepartmentEntity.DepartmentEvent, Offset, _, Offset, NotUsed] =
      FlowWithContext[DepartmentEntity.DepartmentEvent, Offset]
        .map(event => toProducerMessage(kafkaTopic, event))
        .via(Producer.flowWithContext[String, DepartmentEntity.DepartmentEvent, Offset](settings))

    CassandraJournalEventProcessor.create(
      DepartmentKafkaProducerName,
      DepartmentKafkaOffsetNamePrefix,
      DepartmentAggregate.departmentEventTagger,
      handleEvent,
      offsetStore,
      keepAlive
    )
  }

  def createWithProjection(
      kafkaTopic: String
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit = {

    val settings = createKafkaProducerSettings()

    val handleEvent: FlowWithContext[EventEnvelope[DepartmentEntity.DepartmentEvent], ProjectionContext, Done, ProjectionContext, _] =
      FlowWithContext[EventEnvelope[DepartmentEntity.DepartmentEvent], ProjectionContext]
        .map(_.event)
        .map(event => toProducerMessage(kafkaTopic, event))
        .via(Producer.flowWithContext[String, DepartmentEntity.DepartmentEvent, ProjectionContext](settings))
        .map(_ => Done)

    CassandraProjectionJournalEventProcessor.create(
      DepartmentKafkaProducerName,
      DepartmentKafkaOffsetNamePrefix,
      DepartmentAggregate.departmentEventTagger,
      handleEvent,
      keepAlive
    )
  }

  val toProducerMessage
      : (String, DepartmentEntity.DepartmentEvent) => ProducerMessage.Envelope[String, DepartmentEntity.DepartmentEvent, NotUsed] =
    (kafkaTopic, event) => {
      val key     = departmentEventKafkaPartitionKey(event)
      val record  = new ProducerRecord(kafkaTopic, key, event)
      val message = ProducerMessage.single(record)
      message
    }

  val departmentEventKafkaPartitionKey: (DepartmentEntity.DepartmentEvent => String) = event => event.entityId

  val departmentEventProtoKafkaSerializer: Serializer[DepartmentEntity.DepartmentEvent] =
    (_: String, data: DepartmentEntity.DepartmentEvent) => {
      data.asInstanceOf[DepartmentPayloadEvent].toByteArray
    }
}
