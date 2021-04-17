package com.jc.logging

import ch.qos.logback.classic
import ch.qos.logback.classic.{ Level, LoggerContext }
import org.slf4j.Logger
import org.slf4j.impl.StaticLoggerBinder

//org.springframework.boot.logging
trait LoggingSystem {
  def getSupportedLogLevels: Set[LoggingSystem.LogLevel]

  def getLoggerConfiguration(name: String): Option[LoggingSystem.LoggerConfiguration]

  def getLoggerConfigurations: List[LoggingSystem.LoggerConfiguration]

  def setLogLevel(name: String, level: LoggingSystem.LogLevel): Boolean
}

object LoggingSystem {

  sealed trait LogLevel extends Product

  object LogLevel {
    final case object TRACE extends LogLevel
    final case object DEBUG extends LogLevel
    final case object INFO  extends LogLevel
    final case object WARN  extends LogLevel
    final case object ERROR extends LogLevel
    final case object FATAL extends LogLevel
    final case object OFF   extends LogLevel

    import io.circe.generic.extras.semiauto._
    import io.circe.{ Decoder, Encoder }

    implicit val decoder: Decoder[LogLevel] = deriveEnumerationDecoder[LogLevel]
    implicit val encoder: Encoder[LogLevel] = deriveEnumerationEncoder[LogLevel]
  }

  final case class LogLevelMapping[L](mapping: Map[LogLevel, List[L]]) {
    val toLogger: Map[LogLevel, L] = mapping.map { case (k, vs) =>
      k -> vs.head
    }

    val fromLogger: Map[L, LogLevel] = mapping.flatMap { case (k, vs) =>
      vs.map(v => v -> k)
    }
  }

  final case class LoggerConfiguration(name: String, configuredLevel: LogLevel, effectiveLevel: LogLevel)

  object LoggerConfiguration {
    import io.circe.generic.semiauto._
    import io.circe.{ Decoder, Encoder }

    implicit val decoder: Decoder[LoggerConfiguration] = deriveDecoder[LoggerConfiguration]
    implicit val encoder: Encoder[LoggerConfiguration] = deriveEncoder[LoggerConfiguration]
  }

  def loggerConfigurationOrdering(rootLoggerName: String): Ordering[LoggerConfiguration] = new Ordering[LoggerConfiguration] {
    override def compare(o1: LoggerConfiguration, o2: LoggerConfiguration): Int =
      if (rootLoggerName == o1.name) -1
      else if (rootLoggerName == o2.name) 1
      else o1.name.compareTo(o2.name)
  }
}

final class LogbackLoggingSystem(loggerContext: LoggerContext) extends LoggingSystem {

  implicit private val ordering = LoggingSystem.loggerConfigurationOrdering(Logger.ROOT_LOGGER_NAME)

  private def getLogger(name: String): Option[classic.Logger] = {
    val loggerName = if (name.isBlank) Logger.ROOT_LOGGER_NAME else name
    Option(loggerContext.getLogger(loggerName))
  }

  override val getSupportedLogLevels: Set[LoggingSystem.LogLevel] =
    LogbackLoggingSystem.logLevelMapping.toLogger.keySet

  override def getLoggerConfiguration(name: String): Option[LoggingSystem.LoggerConfiguration] =
    getLogger(name).map(LogbackLoggingSystem.toLoggerConfiguration)

  override def getLoggerConfigurations: List[LoggingSystem.LoggerConfiguration] = {
    import scala.jdk.CollectionConverters._
    loggerContext.getLoggerList.asScala.toList.map(LogbackLoggingSystem.toLoggerConfiguration).sorted
  }

  override def setLogLevel(name: String, level: LoggingSystem.LogLevel): Boolean = {
    val maybeLogger = getLogger(name)
    maybeLogger.foreach { logger =>
      val loggerLevel = LogbackLoggingSystem.logLevelMapping.toLogger(level)
      logger.setLevel(loggerLevel)
    }

    maybeLogger.isDefined
  }
}

object LogbackLoggingSystem {

  def apply(): LogbackLoggingSystem = {
    val loggerContext: LoggerContext = {
      val factory = StaticLoggerBinder.getSingleton.getLoggerFactory
      assert(factory.isInstanceOf[LoggerContext], "LoggerFactory is not a Logback LoggerContext")
      factory.asInstanceOf[LoggerContext]
    }
    new LogbackLoggingSystem(loggerContext)
  }

  val logLevelMapping: LoggingSystem.LogLevelMapping[Level] = LoggingSystem.LogLevelMapping(
    Map(
      (LoggingSystem.LogLevel.TRACE, Level.TRACE :: Level.ALL :: Nil),
      (LoggingSystem.LogLevel.DEBUG, Level.DEBUG :: Nil),
      (LoggingSystem.LogLevel.INFO, Level.INFO :: Nil),
      (LoggingSystem.LogLevel.WARN, Level.WARN :: Nil),
      (LoggingSystem.LogLevel.ERROR, Level.ERROR :: Nil),
      (LoggingSystem.LogLevel.FATAL, Level.ERROR :: Nil),
      (LoggingSystem.LogLevel.OFF, Level.OFF :: Nil)
    )
  )

  def toLoggerConfiguration(logger: classic.Logger): LoggingSystem.LoggerConfiguration = {
    val effectiveLevel = logLevelMapping.fromLogger.getOrElse(logger.getEffectiveLevel, LoggingSystem.LogLevel.OFF)
    val level          = logLevelMapping.fromLogger.getOrElse(logger.getLevel, effectiveLevel)
    val name           = if (Option(logger.getName).forall(_.isBlank)) Logger.ROOT_LOGGER_NAME else logger.getName
    LoggingSystem.LoggerConfiguration(name, level, effectiveLevel)
  }
}
