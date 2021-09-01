package com.jc.logging.api

import akka.http.scaladsl.model.{ HttpHeader, IllegalRequestException, StatusCodes }
import akka.http.scaladsl.server.{ Directives, Route }
import akka.http.scaladsl.util.FastFuture
import com.jc.logging.LoggingSystem
import com.jc.logging.openapi.definitions.{ LogLevel, LoggerConfiguration, LoggerConfigurationRes, LoggerConfigurationsRes }
import com.jc.logging.openapi.logging.{ LoggingHandler, LoggingResource }

import scala.concurrent.{ ExecutionContext, Future }

object LoggingSystemOpenApi {

  /** using [[LoggingSystem.LogLevelMapping]] for api <-> logging system level mapping
    */
  private val logLevelMapping: LoggingSystem.LogLevelMapping[LogLevel] = LoggingSystem.LogLevelMapping(
    Seq(
      (LoggingSystem.LogLevel.TRACE, LogLevel.Trace),
      (LoggingSystem.LogLevel.DEBUG, LogLevel.Debug),
      (LoggingSystem.LogLevel.INFO, LogLevel.Info),
      (LoggingSystem.LogLevel.WARN, LogLevel.Warn),
      (LoggingSystem.LogLevel.ERROR, LogLevel.Error),
      (LoggingSystem.LogLevel.FATAL, LogLevel.Fatal),
      (LoggingSystem.LogLevel.OFF, LogLevel.Off)
    )
  )

  def route(
      loggingSystem: LoggingSystem,
      isAuthenticated: Seq[HttpHeader] => Boolean
  )(implicit ec: ExecutionContext, mat: akka.stream.Materializer): Route =
    LoggingResource.routes(
      handler(loggingSystem, isAuthenticated),
      _ => Directives.extractRequest.map(req => req.headers)
    )

  def handler(
      loggingSystem: LoggingSystem,
      isAuthenticated: Seq[HttpHeader] => Boolean
  )(implicit ec: ExecutionContext): LoggingHandler[Seq[HttpHeader]] = {

    def authenticated[R](headers: Seq[HttpHeader])(fn: () => R): Future[R] =
      if (isAuthenticated(headers)) {
        FastFuture.successful(fn())
      } else FastFuture.failed(IllegalRequestException(StatusCodes.Unauthorized))

    def getSupportedLogLevels: Seq[LogLevel] =
      loggingSystem.getSupportedLogLevels.map(logLevelMapping.toLogger).toSeq

    def toApiLoggerConfiguration(configuration: LoggingSystem.LoggerConfiguration): LoggerConfiguration =
      LoggerConfiguration(
        configuration.name,
        logLevelMapping.toLogger(configuration.effectiveLevel),
        configuration.configuredLevel.flatMap(logLevelMapping.toLogger.get)
      )

    new LoggingHandler[Seq[HttpHeader]] {
      override def getLoggerConfigurations(respond: LoggingResource.GetLoggerConfigurationsResponse.type)()(
          extracted: Seq[HttpHeader]
      ): Future[LoggingResource.GetLoggerConfigurationsResponse] =
        authenticated(extracted) { () =>
          val configurations = loggingSystem.getLoggerConfigurations.map(toApiLoggerConfiguration)
          respond.OK(LoggerConfigurationsRes(configurations, getSupportedLogLevels))
        }

      override def getLoggerConfiguration(respond: LoggingResource.GetLoggerConfigurationResponse.type)(name: String)(
          extracted: Seq[HttpHeader]
      ): Future[LoggingResource.GetLoggerConfigurationResponse] =
        authenticated(extracted) { () =>
          val configuration = loggingSystem.getLoggerConfiguration(name).map(toApiLoggerConfiguration)
          LoggerConfigurationRes(configuration, getSupportedLogLevels)
        }
    }
  }
}
