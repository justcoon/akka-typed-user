package com.jc.user.service

import akka.actor.typed.ActorSystem
import akka.kafka.scaladsl.{ DiscoverySupport, Producer }
import akka.kafka.{ ProducerMessage, ProducerSettings }
import akka.projection.ProjectionContext
import akka.projection.eventsourced.EventEnvelope
import akka.stream.Materializer
import akka.stream.scaladsl.FlowWithContext
import akka.{ Done, NotUsed }
import com.jc.cqrs.processor.CassandraJournalEventProcessor
import com.jc.user.domain.proto.UserPayloadEvent
import com.jc.user.domain.{ UserAggregate, UserEntity }
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{ Serializer, StringSerializer }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ FiniteDuration, _ }

object UserKafkaProducer {
  val UserKafkaOffsetNamePrefix = "userKafka"

  val UserKafkaProducerName = "userKafkaProducer"

  val keepAlive: FiniteDuration = 3.seconds

  private def createKafkaProducerSettings()(implicit system: ActorSystem[_]): ProducerSettings[String, UserEntity.UserEvent] = {
    val producerConfig = system.settings.config.getConfig(ProducerSettings.configPath)
    ProducerSettings(system, new StringSerializer, userEventProtoKafkaSerializer)
      .withEnrichAsync(DiscoverySupport.producerBootstrapServers(producerConfig))
  }

  def create(
      kafkaTopic: String,
      keepAliveInterval: FiniteDuration = UserKafkaProducer.keepAlive
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit = {

    val settings = createKafkaProducerSettings()

    val handleEvent: FlowWithContext[EventEnvelope[UserEntity.UserEvent], ProjectionContext, Done, ProjectionContext, _] =
      FlowWithContext[EventEnvelope[UserEntity.UserEvent], ProjectionContext]
        .map(event => toProducerMessage(kafkaTopic, event.event))
        .via(Producer.flowWithContext[String, UserEntity.UserEvent, ProjectionContext](settings))
        .map(_ => Done)

    CassandraJournalEventProcessor.create(
      UserKafkaProducerName,
      UserKafkaOffsetNamePrefix,
      UserAggregate.userEventTagger,
      handleEvent,
      keepAliveInterval
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
