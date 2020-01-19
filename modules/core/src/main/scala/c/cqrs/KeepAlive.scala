package c.cqrs

import akka.actor.typed.{ ActorSystem, Behavior }
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.{ ClusterSharding, EntityTypeKey }
import akka.cluster.typed.{ ClusterSingleton, ClusterSingletonSettings, SingletonActor }

import scala.concurrent.duration.FiniteDuration

object KeepAlive {

  def create[C](name: String, entityIds: Iterable[String], entityKey: EntityTypeKey[C], ping: C, keepAliveInterval: FiniteDuration)(
      implicit system: ActorSystem[_]
  ): Unit =
    ClusterSingleton(system).init(
      SingletonActor(KeepAliveActor(entityIds, entityKey, ping, keepAliveInterval), s"KeepAlive-${name}")
        .withSettings(ClusterSingletonSettings(system) /*.withRole("read-model")*/ )
    )

}

object KeepAliveActor {
  case object Probe

  def apply[C](
      entityIds: Iterable[String],
      entityKey: EntityTypeKey[C],
      ping: C,
      keepAliveInterval: FiniteDuration
  ): Behavior[Probe.type] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val sharding = ClusterSharding(context.system)

        timers.startTimerWithFixedDelay(Probe, Probe, keepAliveInterval)

        Behaviors.receiveMessage { _ =>
          entityIds.foreach { id =>
            sharding.entityRefFor(entityKey, id) ! ping
          }
          Behaviors.same
        }
      }
    }

}
