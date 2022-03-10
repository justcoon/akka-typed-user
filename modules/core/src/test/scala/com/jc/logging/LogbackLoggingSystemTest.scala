package com.jc.logging

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers._
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.LoggerFactory

class LogbackLoggingSystemTest extends AnyWordSpecLike with should.Matchers {

  "LoggingSystem" must {
    import io.circe.syntax._

    "LogLevel json encode decode" in {
      val level: LoggingSystem.LogLevel = LoggingSystem.LogLevel.INFO
      val json                          = level.asJson.noSpaces
      val res                           = io.circe.parser.decode[LoggingSystem.LogLevel](json).toOption
      Some(level) shouldBe res
    }

    "LoggerConfiguration json encode decode" in {
      val cfg  = LoggingSystem.LoggerConfiguration("root", LoggingSystem.LogLevel.INFO, Some(LoggingSystem.LogLevel.DEBUG))
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

      cfg shouldBe Some(LoggingSystem.LoggerConfiguration("com.jc", LoggingSystem.LogLevel.DEBUG, Some(LoggingSystem.LogLevel.DEBUG)))
    }

    "setLogLevel" in {
      val res1 = loggingSystem.setLogLevel("com.jc", Some(LoggingSystem.LogLevel.INFO))
      val cfg1 = loggingSystem.getLoggerConfiguration("com.jc")

      res1 shouldBe true
      cfg1 shouldBe Some(LoggingSystem.LoggerConfiguration("com.jc", LoggingSystem.LogLevel.INFO, Some(LoggingSystem.LogLevel.INFO)))

      val res2 = loggingSystem.setLogLevel("com.jc", None)
      val cfg2 = loggingSystem.getLoggerConfiguration("com.jc")

      res2 shouldBe true
      cfg2 shouldBe Some(LoggingSystem.LoggerConfiguration("com.jc", LoggingSystem.LogLevel.INFO, None))

      val res3 = loggingSystem.setLogLevel("xyz", None)
      val cfg3 = loggingSystem.getLoggerConfiguration("xyz")

      res3 shouldBe false
      cfg3 shouldBe None

      LoggerFactory.getLogger("xyz")

      val res4 = loggingSystem.setLogLevel("xyz", None)
      val cfg4 = loggingSystem.getLoggerConfiguration("xyz")

      res4 shouldBe true
      cfg4 shouldBe Some(LoggingSystem.LoggerConfiguration("xyz", LoggingSystem.LogLevel.INFO, None))
    }

    "getLoggerConfigurations" in {
      val cfgs = loggingSystem.getLoggerConfigurations

      cfgs.isEmpty shouldBe false
    }

  }

}
