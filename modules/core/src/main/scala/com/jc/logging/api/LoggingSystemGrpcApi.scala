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
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.dsl._

import scala.concurrent.{ ExecutionContext, Future }

object LoggingSystemGrpcApi {

  val logLevelMapping: LoggingSystem.LogLevelMapping[LogLevel] = LoggingSystem.LogLevelMapping(
    Map(
      (LoggingSystem.LogLevel.TRACE, LogLevel.TRACE :: Nil),
      (LoggingSystem.LogLevel.DEBUG, LogLevel.DEBUG :: Nil),
      (LoggingSystem.LogLevel.INFO, LogLevel.INFO :: Nil),
      (LoggingSystem.LogLevel.WARN, LogLevel.WARN :: Nil),
      (LoggingSystem.LogLevel.ERROR, LogLevel.ERROR :: Nil),
      (LoggingSystem.LogLevel.FATAL, LogLevel.FATAL :: Nil),
      (LoggingSystem.LogLevel.OFF, LogLevel.OFF :: Nil)
    )
  )

  implicit val toLoggerLevelTransformer: Transformer[LoggingSystem.LogLevel, LogLevel]   = src => logLevelMapping.toLogger(src)
  implicit val fromLoggerLevelTransformer: Transformer[LogLevel, LoggingSystem.LogLevel] = src => logLevelMapping.fromLogger(src)

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

    new LoggingSystemApiServicePowerApi {
      override def setLoggerConfiguration(in: SetLoggerConfigurationReq, metadata: Metadata): Future[LoggerConfigurationRes] =
        authenticated(metadata) { () =>
          val res = loggingSystem.setLogLevel(in.name, logLevelMapping.fromLogger(in.level))
          val cfg = if (res) {
            loggingSystem.getLoggerConfiguration(in.name).map(_.transformInto[LoggerConfiguration])
          } else None
          LoggerConfigurationRes(cfg, loggingSystem.getSupportedLogLevels.map(logLevelMapping.toLogger).toSeq)
        }

      override def getLoggerConfiguration(in: GetLoggerConfigurationReq, metadata: Metadata): Future[LoggerConfigurationRes] =
        authenticated(metadata) { () =>
          val cfg = loggingSystem.getLoggerConfiguration(in.name).map(_.transformInto[LoggerConfiguration])
          LoggerConfigurationRes(cfg, loggingSystem.getSupportedLogLevels.map(logLevelMapping.toLogger).toSeq)
        }

      override def getLoggerConfigurations(in: GetLoggerConfigurationsReq, metadata: Metadata): Future[LoggerConfigurationsRes] =
        authenticated(metadata) { () =>
          val cfg = loggingSystem.getLoggerConfigurations.map(_.transformInto[LoggerConfiguration])
          LoggerConfigurationsRes(cfg, loggingSystem.getSupportedLogLevels.map(logLevelMapping.toLogger).toSeq)
        }
    }
  }
}
