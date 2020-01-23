package c.user.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.kafka.{ ProducerMessage, ProducerSettings }
import akka.kafka.scaladsl.Producer
import akka.persistence.query.Offset
import akka.stream.Materializer
import akka.stream.scaladsl.FlowWithContext
import c.cqrs.offsetstore.OffsetStore
import c.cqrs.processor.CassandraJournalEventProcessor
import c.user.domain.UserEntity
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
        .map { event =>
          val key     = getKafkaPartitionKey(event)
          val record  = new ProducerRecord(kafkaTopic, key, event)
          val message = ProducerMessage.single(record)
          message
        }
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

  def getKafkaPartitionKey(event: UserEntity.UserEvent): String =
    event.entityId

  val userEventProtoKafkaSerializer: Serializer[UserEntity.UserEvent] = (_: String, data: UserEntity.UserEvent) => {
    data.asInstanceOf[scalapb.GeneratedMessage].toByteArray
  }
}
