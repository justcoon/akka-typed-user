package c.cqrs

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior, PostStop}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.persistence.query.Offset
import akka.stream.scaladsl.{Flow, RestartSource, Sink, Source}
import akka.stream.{KillSwitches, Materializer, SharedKillSwitch}

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContext, Future}

object EventProcessor {

  def create[E](
      name: String,
      shards: Iterable[String],
      eventProcessorStream: String => EventProcessorStream[E],
      keepAliveInterval: FiniteDuration
  )(
      implicit system: ActorSystem[_]
  ) {
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

case class EventStreamElement[E](offset: Offset, event: E)


trait EventProcessorStream[E] {

  def name: String

  def runStream(killSwitch: SharedKillSwitch): Unit
}

case class EventProcessorStreamConfig(
    offsetTimeout: FiniteDuration = 5.seconds,
    minBackoff: FiniteDuration = 3.seconds,
    maxBackoff: FiniteDuration = 30.seconds,
    randomBackoffFactor: Double = 0.2
)

object EventProcessorStream {

  def create[E](
      streamName: String,
      offsetName: String => String,
      initialOffset: Option[Offset] => Offset,
      offsetStore: OffsetStore[Offset, Future],
      eventStreamFactory: (String, Offset) => Source[EventStreamElement[E], NotUsed],
      handleEvent: Flow[EventStreamElement[E], EventStreamElement[E], NotUsed],
      config: EventProcessorStreamConfig = EventProcessorStreamConfig()
  )(implicit mat: Materializer, ec: ExecutionContext): EventProcessorStream[E] =
    new EventProcessorStream[E] {
      override val name: String = streamName

      override def runStream(killSwitch: SharedKillSwitch): Unit = {

        val backoffSource =
          RestartSource.withBackoff(
            config.minBackoff,
            config.maxBackoff,
            config.randomBackoffFactor
          ) { () =>
            val oName        = offsetName(name)
            val futureOffset = offsetStore.loadOffset(oName)
            Source
              .future(futureOffset)
              .initialTimeout(config.offsetTimeout)
              .flatMapConcat { storedOffset =>
                val offset            = initialOffset(storedOffset)
                val eventStreamSource = eventStreamFactory(name, offset)

                eventStreamSource.via(handleEvent)
              }
              .mapAsync(1)(eventStreamElement => offsetStore.storeOffset(oName, eventStreamElement.offset))
          }

        backoffSource
          .via(killSwitch.flow)
          .runWith(Sink.ignore)
      }
    }

}
