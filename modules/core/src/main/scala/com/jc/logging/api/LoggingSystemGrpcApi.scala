package com.jc.logging.api

import akka.actor.ActorSystem
import akka.grpc.scaladsl.Metadata
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.util.FastFuture
import com.jc.logging.LoggingSystem
import com.jc.logging.proto.{
  GetLoggerConfigurationReq,
  GetLoggerConfigurationsReq,
  LogLevel,
  LoggerConfiguration,
  LoggerConfigurationRes,
  LoggerConfigurationsRes,
  LoggingSystemApiServicePowerApi,
  LoggingSystemApiServicePowerApiHandler,
  SetLoggerConfigurationReq
}

import scala.concurrent.{ ExecutionContext, Future }

object LoggingSystemGrpcApi {

  /** using [[LoggingSystem.LogLevelMapping]] for api <-> logging system level mapping
    *
    * [[LogLevel.NONE]] is not in mapping, it is specially handled like: log level not defined
    */
  private val logLevelMapping: LoggingSystem.LogLevelMapping[LogLevel] = LoggingSystem.LogLevelMapping(
    Seq(
      (LoggingSystem.LogLevel.TRACE, LogLevel.TRACE),
      (LoggingSystem.LogLevel.DEBUG, LogLevel.DEBUG),
      (LoggingSystem.LogLevel.INFO, LogLevel.INFO),
      (LoggingSystem.LogLevel.WARN, LogLevel.WARN),
      (LoggingSystem.LogLevel.ERROR, LogLevel.ERROR),
      (LoggingSystem.LogLevel.FATAL, LogLevel.FATAL),
      (LoggingSystem.LogLevel.OFF, LogLevel.OFF)
    )
  )

  def handler(
      loggingSystem: LoggingSystem,
      isAuthenticated: Metadata => Boolean
  )(implicit
      ec: ExecutionContext,
      sys: ActorSystem
  ): HttpRequest => Future[HttpResponse] =
    LoggingSystemApiServicePowerApiHandler(service(loggingSystem, isAuthenticated))

  def partialHandler(
      loggingSystem: LoggingSystem,
      isAuthenticated: Metadata => Boolean
  )(implicit
      ec: ExecutionContext,
      sys: ActorSystem
  ): PartialFunction[HttpRequest, Future[HttpResponse]] =
    LoggingSystemApiServicePowerApiHandler.partial(service(loggingSystem, isAuthenticated))

  def service(
      loggingSystem: LoggingSystem,
      isAuthenticated: Metadata => Boolean
  )(implicit ec: ExecutionContext): LoggingSystemApiServicePowerApi = {

    def authenticated[R](metadata: Metadata)(fn: () => R): Future[R] =
      if (isAuthenticated(metadata)) {
        FastFuture.successful(fn())
      } else FastFuture.failed(new akka.grpc.GrpcServiceException(io.grpc.Status.UNAUTHENTICATED, metadata))

    def getSupportedLogLevels: Seq[LogLevel] =
      LogLevel.NONE +: loggingSystem.getSupportedLogLevels.map(logLevelMapping.toLogger).toSeq

    def toApiLoggerConfiguration(configuration: LoggingSystem.LoggerConfiguration): LoggerConfiguration =
      LoggerConfiguration(
        configuration.name,
        logLevelMapping.toLogger(configuration.effectiveLevel),
        configuration.configuredLevel.flatMap(logLevelMapping.toLogger.get).getOrElse(LogLevel.NONE)
      )

    new LoggingSystemApiServicePowerApi {

      override def setLoggerConfiguration(in: SetLoggerConfigurationReq, metadata: Metadata): Future[LoggerConfigurationRes] =
        authenticated(metadata) { () =>
          val res = loggingSystem.setLogLevel(in.name, logLevelMapping.fromLogger.get(in.level))
          val configuration = if (res) {
            loggingSystem.getLoggerConfiguration(in.name).map(toApiLoggerConfiguration)
          } else None
          LoggerConfigurationRes(configuration, getSupportedLogLevels)
        }

      override def getLoggerConfiguration(in: GetLoggerConfigurationReq, metadata: Metadata): Future[LoggerConfigurationRes] =
        authenticated(metadata) { () =>
          val configuration = loggingSystem.getLoggerConfiguration(in.name).map(toApiLoggerConfiguration)
          LoggerConfigurationRes(configuration, getSupportedLogLevels)
        }

      override def getLoggerConfigurations(in: GetLoggerConfigurationsReq, metadata: Metadata): Future[LoggerConfigurationsRes] =
        authenticated(metadata) { () =>
          val configurations = loggingSystem.getLoggerConfigurations.map(toApiLoggerConfiguration)
          LoggerConfigurationsRes(configurations, getSupportedLogLevels)
        }
    }
  }
}
