package com.jc.logging

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers._
import org.scalatest.wordspec.AnyWordSpecLike

class LogbackLoggingSystemTest extends AnyWordSpecLike with should.Matchers with BeforeAndAfterAll {

  "LoggingSystem" must {
    import io.circe.syntax._

    "LogLevel json encode decode" in {
      val level: LoggingSystem.LogLevel = LoggingSystem.LogLevel.INFO
      val json                          = level.asJson.noSpaces
      val res                           = io.circe.parser.decode[LoggingSystem.LogLevel](json).toOption
      Some(level) shouldBe res
    }

    "LoggerConfiguration json encode decode" in {
      val cfg  = LoggingSystem.LoggerConfiguration("root", LoggingSystem.LogLevel.INFO, LoggingSystem.LogLevel.DEBUG)
      val json = cfg.asJson.noSpaces
      val res  = io.circe.parser.decode[LoggingSystem.LoggerConfiguration](json).toOption
      Some(cfg) shouldBe res
    }

  }

  "LogbackLoggingSystem" must {

    val loggingSystem = LogbackLoggingSystem()

    "getSupportedLogLevels" in {
      loggingSystem.getSupportedLogLevels.isEmpty shouldBe false
    }

    "getLoggerConfiguration" in {
      val cfg = loggingSystem.getLoggerConfiguration("com.jc")

      cfg shouldBe Some(LoggingSystem.LoggerConfiguration("com.jc", LoggingSystem.LogLevel.DEBUG, LoggingSystem.LogLevel.DEBUG))
    }

    "setLoggerConfiguration" in {
      val res = loggingSystem.setLogLevel("com.jc", LoggingSystem.LogLevel.INFO)
      val cfg = loggingSystem.getLoggerConfiguration("com.jc")

      res shouldBe true
      cfg shouldBe Some(LoggingSystem.LoggerConfiguration("com.jc", LoggingSystem.LogLevel.INFO, LoggingSystem.LogLevel.INFO))
    }

    "getLoggerConfigurations" in {
      val cfgs = loggingSystem.getLoggerConfigurations

      cfgs.isEmpty shouldBe false
    }

  }

}
