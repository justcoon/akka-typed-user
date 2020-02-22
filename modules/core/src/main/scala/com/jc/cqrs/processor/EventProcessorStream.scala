package com.jc.cqrs.processor

import akka.NotUsed
import akka.persistence.query.Offset
import akka.stream.{ Materializer, SharedKillSwitch }
import akka.stream.scaladsl.{ FlowWithContext, RestartSource, Sink, Source, SourceWithContext }
import com.jc.cqrs.offsetstore.OffsetStore

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.{ FiniteDuration, _ }

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
//https://blog.softwaremill.com/painlessly-passing-message-context-through-akka-streams-1615b11efc2c
object EventProcessorStream {

  def create[E](
      streamName: String,
      offsetName: String => String,
      initialOffset: Option[Offset] => Offset,
      offsetStore: OffsetStore[Offset, Future],
      eventStreamFactory: (String, Offset) => SourceWithContext[E, Offset, NotUsed],
      handleEvent: FlowWithContext[E, Offset, _, Offset, NotUsed],
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

                eventStreamSource
              }
              .via(handleEvent)
              .mapAsync(1) {
                case (_, offset) =>
                  offsetStore.storeOffset(oName, offset)
              }
          }

        backoffSource
          .via(killSwitch.flow)
          .runWith(Sink.ignore)
      }
    }

}
