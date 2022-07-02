package com.jc.logging

import akka.actor.typed.scaladsl.ActorContext
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.jc.support.PubSub

final case class ClusterSetLoggingSystem(loggingSystem: LoggingSystem)(implicit actorContext: ActorContext[_], materializer: Materializer)
    extends LoggingSystem {

  private val pubSub = ClusterSetLoggingSystem.createPubSub()

  pubSub.subscriber
    .map { msg =>
      loggingSystem.setLogLevel(msg.name, msg.level)
    }
    .runWith(Sink.ignore)

  override def getSupportedLogLevels: Set[LoggingSystem.LogLevel] =
    loggingSystem.getSupportedLogLevels

  override def getLoggerConfiguration(name: String): Option[LoggingSystem.LoggerConfiguration] =
    loggingSystem.getLoggerConfiguration(name)

  override def getLoggerConfigurations: List[LoggingSystem.LoggerConfiguration] =
    loggingSystem.getLoggerConfigurations

  override def setLogLevel(name: String, level: Option[LoggingSystem.LogLevel]): Boolean = {
    val res = loggingSystem.setLogLevel(name, level)
    if (res) {
      pubSub.publish(ClusterSetLoggingSystem.SetLogLevel(name, level))
    }
    res
  }
}

object ClusterSetLoggingSystem {

  private[logging] final case class SetLogLevel(name: String, level: Option[LoggingSystem.LogLevel])

  private[logging] def createPubSub()(implicit actorContext: ActorContext[_]): PubSub[SetLogLevel] =
    PubSub("LoggingSystem")
}
