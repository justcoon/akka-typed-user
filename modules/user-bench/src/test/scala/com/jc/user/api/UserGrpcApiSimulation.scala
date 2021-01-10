package com.jc.user.api

import com.github.phisgr.gatling.grpc.Predef._
import com.github.phisgr.gatling.pb._
import com.jc.user.api.proto._
import com.jc.user.domain.proto.DepartmentRef
import com.jc.user.domain.DepartmentEntity._
import com.typesafe.config.ConfigFactory
import io.gatling.core.Predef.{ stringToExpression => _, _ }
import io.gatling.core.session.Expression
import pureconfig.ConfigSource
import eu.timepit.refined.auto._

import scala.util.Random

final class UserGrpcApiSimulation extends Simulation {

  val config        = ConfigFactory.load
  val grpcApiConfig = ConfigSource.fromConfig(config.getConfig("grpc-api")).loadOrThrow[HttpApiConfig]

  val grpcConf = grpc(managedChannelBuilder(name = grpcApiConfig.address, port = grpcApiConfig.port).usePlaintext())

  val departmentIdFeeder = Array("d1", "d2", "d3", "d4").map(v => Map("id" -> v)).random

  val countryFeeder = Array("CA", "UK", "UA", "US", "MX").map(v => Map("id" -> v)).random

  val suggestFeeder = Array("bla", "blue", "red", "bl").map(v => Map("id" -> v)).random

  val regUserFeeder = Iterator.continually {
    val deps   = List("d1", "d2", "d3", "d4")
    val name   = Random.alphanumeric.take(5).mkString
    val pass   = Random.alphanumeric.take(5).mkString
    val depRef = DepartmentRef(deps(Random.nextInt(deps.length)).asDepartmentId)
    Map("username" -> name, "email" -> (name + "@test.com"), "pass" -> pass, "department" -> depRef)
  }

  val registerUserPayload: Expression[RegisterUserReq] = RegisterUserReq.defaultInstance
    .updateExpr(
      _.username :~ $("username"),
      _.email :~ $("email"),
      _.pass :~ $("pass"),
      //      _.address :~ $("address"),
      _.department :~ $("department")
    )

  val getDepartmentPayload: Expression[GetDepartmentReq] = GetDepartmentReq(id = "d1")
    .updateExpr(
      _.id :~ $("id")
    )

  val searchUserPayload: Expression[SearchUsersReq] = SearchUsersReq(pageSize = 10)
    .updateExpr(
      _.query :~ $("id")
    )

  val suggestUserPayload: Expression[SuggestUsersReq] = SuggestUsersReq.defaultInstance
    .updateExpr(
      _.query :~ $("id")
    )

  val registerUserSuccessfulCall = grpc("registerUser")
    .rpc(com.jc.user.api.proto.UserApiServiceGrpc.METHOD_REGISTER_USER)
    .payload(registerUserPayload)

  val getDepartmentSuccessfulCall = grpc("getDepartment")
    .rpc(com.jc.user.api.proto.UserApiServiceGrpc.METHOD_GET_DEPARTMENT)
    .payload(getDepartmentPayload)

  val searchUsersSuccessfulCall = grpc("searchUsers")
    .rpc(com.jc.user.api.proto.UserApiServiceGrpc.METHOD_SEARCH_USERS)
    .payload(searchUserPayload)

  val suggestUsersSuccessfulCall = grpc("suggestUsers")
    .rpc(com.jc.user.api.proto.UserApiServiceGrpc.METHOD_SUGGEST_USERS)
    .payload(suggestUserPayload)

  val s = scenario("UserGrpcApi")
    .feed(regUserFeeder)
    .exec(registerUserSuccessfulCall)
    .feed(departmentIdFeeder)
    .exec(getDepartmentSuccessfulCall)
    .exec(searchUsersSuccessfulCall)
    .feed(countryFeeder)
    .exec(searchUsersSuccessfulCall)
    .feed(suggestFeeder)
    .exec(suggestUsersSuccessfulCall)

  setUp(
    s.inject(atOnceUsers(54))
  ).protocols(grpcConf)
}
