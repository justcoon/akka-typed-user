package c.cqrs

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, SupervisorStrategy }
import akka.cluster.typed.{ ClusterSingleton, SingletonActor }

import scala.concurrent.Future
import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.util.{ Failure, Success }

case class ClusterTaskConfig(
    taskTimeout: FiniteDuration = 5.seconds,
    minBackoff: FiniteDuration = 3.seconds,
    maxBackoff: FiniteDuration = 30.seconds,
    randomBackoffFactor: Double = 0.2
)

object ClusterTask {

  def createSingleton(
      name: String,
      task: () => Future[_],
      config: ClusterTaskConfig = ClusterTaskConfig()
  )(implicit system: ActorSystem[_]): Unit = {

    val singletonManager = ClusterSingleton(system)
    val proxy: ActorRef[ClusterTaskActor.Command] = singletonManager.init(
      SingletonActor(
        Behaviors
          .supervise(ClusterTaskActor(name, task))
          .onFailure[Exception](SupervisorStrategy.restartWithBackoff(config.minBackoff, config.maxBackoff, config.randomBackoffFactor)),
        s"ClusterTask-${name}"
      )
    )

    proxy ! ClusterTaskActor.ExecuteTask
  }

  def createLocal(
      name: String,
      task: () => Future[_],
      config: ClusterTaskConfig = ClusterTaskConfig()
  )(implicit system: ActorSystem[_]): Unit = {

    val actor: ActorRef[ClusterTaskActor.Command] = system.systemActorOf(
      Behaviors
        .supervise(ClusterTaskActor(name, task))
        .onFailure[Exception](SupervisorStrategy.restartWithBackoff(config.minBackoff, config.maxBackoff, config.randomBackoffFactor)),
      s"ClusterTask-${name}"
    )

    actor ! ClusterTaskActor.ExecuteTask
  }
}

object ClusterTaskActor {

  trait Command

  case object ExecuteTask                     extends Command
  case object TaskCompleted                   extends Command
  case class TaskFailed(exception: Throwable) extends Command

  def apply(name: String, task: () => Future[_]): Behavior[Command] = {

    def initial() =
      Behaviors
        .receive[Command] { (context, command) =>
          command match {
            case ExecuteTask =>
              context.log.debug("executing task: {}", name)

              context.pipeToSelf(task()) {
                case Success(_)         => TaskCompleted
                case Failure(exception) => TaskFailed(exception)
              }

              executing()
          }
        }

    def executing() =
      Behaviors
        .receive[Command] { (context, command) =>
          command match {
            case ExecuteTask =>
              // we are processing
              Behaviors.same
            case TaskCompleted =>
              context.log.debug("completed task: {}", name)
//              completed()
              Behaviors.stopped

            case TaskFailed(exception) =>
              context.log.error("failed task: {}", name, exception.getMessage)
              throw exception
          }
        }

//    def completed() =
//      Behaviors
//        .receive[Command] { (context, command) =>
//          command match {
//            case ExecuteTask =>
//              Behaviors.same
//          }
//        }

    initial()
  }
}
