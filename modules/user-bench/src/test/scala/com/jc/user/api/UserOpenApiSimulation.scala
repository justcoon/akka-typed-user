package com.jc.user.api

import com.typesafe.config.ConfigFactory
import io.gatling.core.Predef.{ stringToExpression => _, _ }
import io.gatling.http.Predef.http
import io.gatling.http.protocol.HttpProtocolBuilder
import io.gatling.http.request.builder.HttpRequestBuilder
import pureconfig.ConfigSource

import scala.util.Random

final class UserOpenApiSimulation extends Simulation {

  import Feeders._

  val config    = ConfigFactory.load
  val apiConfig = ConfigSource.fromConfig(config.getConfig("rest-api")).loadOrThrow[HttpApiConfig]

  val httpConf: HttpProtocolBuilder = http.baseUrl(s"http://${apiConfig.address.value}:${apiConfig.port.value}")

  val jwtAuthHeader = "Authorization"

  val jwtToken =
    "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzUxMiJ9.eyJpc3MiOiJ6aW8tdXNlci1zZWFyY2giLCJzdWIiOiJ0ZXN0IiwiZXhwIjoyMjE1Mjg1MDU5LCJpYXQiOjE2MTA0ODUwNTl9.MONRFj9rSf23AV7rCCPfkyqHWVhHkI42R93CK5QHpxMSsb9oc_65YpWsmDfdX2IzzKVqdSP59rL_3_CRK_C4dg"

  val regUserFeeder = Iterator.continually {
    val deps  = List("d1", "d2", "d3", "d4")
    val name  = Random.alphanumeric.take(5).mkString
    val pass  = Random.alphanumeric.take(5).mkString
    val depId = deps(Random.nextInt(deps.length))
    Map("username" -> name, "email" -> (name + "@test.com"), "pass" -> pass, "departmentId" -> depId)
  }

  val getDepartmentSuccessfulCall: HttpRequestBuilder = http("getDepartment")
    .get { s =>
      val id = s.attributes.getOrElse("id", "d1")
      s"/v1/department/${id}"
    }
    .header(jwtAuthHeader, jwtToken)

  val searchUsersSuccessfulCall: HttpRequestBuilder = http("searchUsers")
    .get { s =>
      val id = s.attributes.getOrElse("id", "")
      s"/v1/user/search?query=${id}&page=0&pageSize=10"
    }
    .header(jwtAuthHeader, jwtToken)

  val suggestUsersSuccessfulCall: HttpRequestBuilder = http("suggestUsers")
    .get { s =>
      val id = s.attributes.getOrElse("id", "")
      s"/v1/user/suggest?query=${id}"
    }
    .header(jwtAuthHeader, jwtToken)

  val s = scenario("UserSearchOpenApi")
    .repeat(100) {
      feed(departmentIdFeeder)
        .exec(getDepartmentSuccessfulCall)
        .exec(searchUsersSuccessfulCall)
        .feed(countryFeeder)
        .exec(searchUsersSuccessfulCall)
        .feed(suggestFeeder)
        .exec(suggestUsersSuccessfulCall)
    }

  setUp(
    s.inject(atOnceUsers(200))
  ).protocols(httpConf)
}
