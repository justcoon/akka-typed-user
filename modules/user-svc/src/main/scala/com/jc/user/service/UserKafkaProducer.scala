package com.jc.user.service

import akka.{ Done, NotUsed }
import akka.actor.typed.ActorSystem
import akka.kafka.{ ProducerMessage, ProducerSettings }
import akka.kafka.scaladsl.Producer
import akka.persistence.query.Offset
import akka.projection.ProjectionContext
import akka.projection.eventsourced.EventEnvelope
import akka.stream.Materializer
import akka.stream.scaladsl.FlowWithContext
import com.jc.cqrs.offsetstore.OffsetStore
import com.jc.cqrs.processor.{ CassandraJournalEventProcessor, CassandraProjectionJournalEventProcessor }
import com.jc.user.domain.UserEntity
import com.jc.user.domain.proto.UserPayloadEvent
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{ Serializer, StringSerializer }

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.concurrent.{ ExecutionContext, Future }

object UserKafkaProducer {
  val UserKafkaOffsetNamePrefix = "userKafka"

  val UserKafkaProducerName = "userKafkaProducer"

  val keepAlive: FiniteDuration = 3.seconds

  def create(
      kafkaTopic: String,
      kafkaBootstrapServers: List[String],
      offsetStore: OffsetStore[Offset, Future]
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit = {

    import akka.actor.typed.scaladsl.adapter._

    val userKafkaProducerSettings =
      ProducerSettings(system.toClassic, new StringSerializer, userEventProtoKafkaSerializer)
        .withBootstrapServers(kafkaBootstrapServers.mkString(","))

    val handleEvent: FlowWithContext[UserEntity.UserEvent, Offset, _, Offset, NotUsed] =
      FlowWithContext[UserEntity.UserEvent, Offset]
        .map(event => toProducerMessage(kafkaTopic, event))
        .via(Producer.flowWithContext[String, UserEntity.UserEvent, Offset](userKafkaProducerSettings))

    CassandraJournalEventProcessor.create(
      UserKafkaProducerName,
      UserKafkaOffsetNamePrefix,
      UserEntity.userEventTagger,
      handleEvent,
      offsetStore,
      keepAlive
    )
  }

  def createWithProjection(
      kafkaTopic: String,
      kafkaBootstrapServers: List[String]
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit = {

    import akka.actor.typed.scaladsl.adapter._

    val userKafkaProducerSettings =
      ProducerSettings(system.toClassic, new StringSerializer, userEventProtoKafkaSerializer)
        .withBootstrapServers(kafkaBootstrapServers.mkString(","))

    val handleEvent: FlowWithContext[EventEnvelope[UserEntity.UserEvent], ProjectionContext, Done, ProjectionContext, _] =
      FlowWithContext[EventEnvelope[UserEntity.UserEvent], ProjectionContext]
        .map(_.event)
        .map(event => toProducerMessage(kafkaTopic, event))
        .via(Producer.flowWithContext[String, UserEntity.UserEvent, ProjectionContext](userKafkaProducerSettings))
        .map(_ => Done)

    CassandraProjectionJournalEventProcessor.create(
      UserKafkaProducerName,
      UserKafkaOffsetNamePrefix,
      UserEntity.userEventTagger,
      handleEvent,
      keepAlive
    )
  }

  val toProducerMessage: (String, UserEntity.UserEvent) => ProducerMessage.Envelope[String, UserEntity.UserEvent, NotUsed] =
    (kafkaTopic, event) => {
      val key     = userEventKafkaPartitionKey(event)
      val record  = new ProducerRecord(kafkaTopic, key, event)
      val message = ProducerMessage.single(record)
      message
    }

  val userEventKafkaPartitionKey: (UserEntity.UserEvent => String) = event => event.entityId

  val userEventProtoKafkaSerializer: Serializer[UserEntity.UserEvent] = (_: String, data: UserEntity.UserEvent) => {
    data.asInstanceOf[UserPayloadEvent].toByteArray
  }
}
