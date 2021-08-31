package com.jc.api.openapi

import io.swagger.v3.core.util.Yaml
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers._
import org.scalatest.wordspec.AnyWordSpecLike

import scala.io.Source

class OpenApiMergerTest extends AnyWordSpecLike with should.Matchers with BeforeAndAfterAll {

  val y1 = Source.fromResource("UserOpenApi.yaml").mkString
  val y2 = Source.fromResource("LoggingOpenApi.yaml").mkString

  "OpenApiMerger" must {

    "merge" in {
      val p1 = OpenApiReader.read(y1)
      val p2 = OpenApiReader.read(y2)

      p1.isRight shouldBe true
      p2.isRight shouldBe true

      val m = for {
        o1 <- p1
        o2 <- p2
      } yield OpenApiMerger.merge(o1, o2)

      m.isRight shouldBe true

      m.foreach { o =>
        val y = Yaml.pretty(o)
        println(y)
      }
    }

  }

}
