package com.jc.support

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.actor.typed.pubsub.Topic
import akka.actor.typed.scaladsl.ActorContext
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.typed.scaladsl.ActorSource

import scala.reflect.ClassTag

final case class PubSubConfig(subscriberBufferSize: Int = 10)

final class PubSub[T](pubSubActorRef: ActorRef[Topic.Command[T]], config: PubSubConfig = PubSubConfig()) {

  def publish(message: T): Unit =
    pubSubActorRef ! Topic.Publish(message)

  def publisher: Sink[T, NotUsed] =
    Sink
      .foreach[T] { message =>
        pubSubActorRef ! Topic.Publish(message)
        ()
      }
      .mapMaterializedValue(_ => NotUsed)

  def subscriber: Source[T, NotUsed] =
    ActorSource
      .actorRef(
        completionMatcher = PartialFunction.empty[T, Unit],
        failureMatcher = PartialFunction.empty[Any, Throwable],
        config.subscriberBufferSize,
        OverflowStrategy.dropHead
      )
      .mapMaterializedValue { ref =>
        pubSubActorRef ! Topic.Subscribe(ref)
        NotUsed
      }
}

object PubSub {

  def getActorName(topic: String): String =
    s"PubSub-${topic}"

  def apply[T: ClassTag](topic: String, config: PubSubConfig = PubSubConfig())(implicit actorContext: ActorContext[_]): PubSub[T] = {
    val name     = getActorName(topic)
    val actorRef = actorContext.spawn(Topic[T](topic), name)
    new PubSub[T](actorRef, config)
  }
}
