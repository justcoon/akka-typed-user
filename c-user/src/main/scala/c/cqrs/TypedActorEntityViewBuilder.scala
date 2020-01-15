package c.cqrs

import akka.NotUsed
import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.{ClusterSingleton, SingletonActor}
import akka.persistence.query.Offset
import akka.stream.scaladsl.{Flow, Keep, RestartSource, Sink, Source}
import akka.stream.{KillSwitch, KillSwitches, Materializer}

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContext, Future}

case class EventStreamElement[E](offset: Offset, event: E)

//trait TypedActorEntityViewBuilder {
//
//  def create[E](
//      name: String,
//      initialOffset: Option[Offset] => Offset,
//      offsetStore: OffsetStore[Future],
//      eventStreamFactory: (String, Offset) => Source[EventStreamElement[E], NotUsed],
//      handleEvent: Flow[EventStreamElement[E], EventStreamElement[E], NotUsed],
//      config: BuilderConfig
//  ): Unit
//}

case class BuilderConfig(
    offsetTimeout: FiniteDuration = 5.seconds,
    minBackoff: FiniteDuration = 3.seconds,
    maxBackoff: FiniteDuration = 30.seconds,
    randomBackoffFactor: Double = 0.2
)

object EntityViewBuilderActor {

  trait Command

  case class StartProcessing(name: String) extends Command
  case object ProcessStopped               extends Command

  def apply[E](
      offsetName: String => String,
      initialOffset: Option[Offset] => Offset,
      offsetStore: OffsetStore[Offset, Future],
      eventStreamFactory: (String, Offset) => Source[EventStreamElement[E], NotUsed],
      handleEvent: Flow[EventStreamElement[E], EventStreamElement[E], NotUsed],
      config: BuilderConfig
  )(implicit mat: Materializer, ec: ExecutionContext): Behavior[Command] = {

    def processing(name: String, shutdown: KillSwitch): Behavior[Command] =
      Behaviors
        .receive[Command] { (context, command) =>
          command match {
            case ProcessStopped =>
              context.log.debug("stopped - {}", name)
              Behaviors.stopped

            case StartProcessing(_) =>
              // we are processing ...
              Behaviors.same
          }
        }
        .receiveSignal {
          case (context, PostStop) =>
            context.log.debug("shutdown - {}", name)
            shutdown.shutdown()
            Behaviors.same
        }

    def initial(): Behavior[Command] =
      Behaviors.receive { (context, command) =>
        command match {
          case StartProcessing(name) =>
            context.log.debug("starting - {}", name)
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

            val (killSwitch, streamDone) = backoffSource
              .viaMat(KillSwitches.single)(Keep.right)
              .toMat(Sink.ignore)(Keep.both)
              .run()

            context.pipeToSelf(streamDone) {
              case _ => ProcessStopped
            }

            processing(name, killSwitch)
        }
      }

    initial()
  }
}

object SingletonActorEntityViewBuilder {

  def create[E](
      name: String,
      offsetName: String => String,
      initialOffset: Option[Offset] => Offset,
      offsetStore: OffsetStore[Offset, Future],
      eventStreamFactory: (String, Offset) => Source[EventStreamElement[E], NotUsed],
      handleEvent: Flow[EventStreamElement[E], EventStreamElement[E], NotUsed],
      config: BuilderConfig = BuilderConfig()
  )(implicit system: ActorSystem[_], mat: Materializer, ec: ExecutionContext): Unit = {

    val singletonManager = ClusterSingleton(system)
    val proxy: ActorRef[EntityViewBuilderActor.Command] = singletonManager.init(
      SingletonActor(
        Behaviors
          .supervise(EntityViewBuilderActor(offsetName, initialOffset, offsetStore, eventStreamFactory, handleEvent, config))
          .onFailure[Exception](SupervisorStrategy.restart),
        s"EntityViewBuilder-${name}"
      )
    )

    proxy ! EntityViewBuilderActor.StartProcessing(name)
  }
}
