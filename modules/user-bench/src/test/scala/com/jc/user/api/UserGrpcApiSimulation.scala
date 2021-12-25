package com.jc.user.api

import com.github.phisgr.gatling.grpc.Predef._
import com.github.phisgr.gatling.pb._
import com.jc.auth.JwtAuthenticator
import com.jc.user.api.proto._
import com.jc.user.domain.proto.DepartmentRef
import com.jc.user.domain.DepartmentEntity._
import com.typesafe.config.ConfigFactory
import io.gatling.core.Predef.{ stringToExpression => _, _ }
import io.gatling.core.session.Expression
import pureconfig.ConfigSource
import eu.timepit.refined.auto._
import io.grpc.Metadata

import scala.util.Random

final class UserGrpcApiSimulation extends Simulation {

  import Feeders._
  import com.jc.user.domain.DepartmentEntity._
  val config    = ConfigFactory.load
  val apiConfig = ConfigSource.fromConfig(config.getConfig("grpc-api")).loadOrThrow[HttpApiConfig]

  //  val mcb1 = ManagedChannelBuilder
  //    .forTarget("service")
  //    .nameResolverFactory(
  //      new GrpcMultiAddressNameResolverFactory(
  //        List(new InetSocketAddress("localhost", 8010), new InetSocketAddress("localhost", 8011), new InetSocketAddress("localhost", 8012))
  //      )
  //    )
  //    .defaultLoadBalancingPolicy("round_robin")
  //    .usePlaintext()

  val mcb      = managedChannelBuilder(name = apiConfig.address, port = apiConfig.port).usePlaintext()
  val grpcConf = grpc(mcb)

  val jwtAuthHeader: Metadata.Key[String] = Metadata.Key.of(JwtAuthenticator.AuthHeader, Metadata.ASCII_STRING_MARSHALLER)

  val jwtToken =
    "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzUxMiJ9.eyJpc3MiOiJ6aW8tdXNlci1zZWFyY2giLCJzdWIiOiJ0ZXN0IiwiZXhwIjoyMjE1MDc4MTYwLCJpYXQiOjE2MTAyNzgxNjB9.CCTmZT-Iy-0bq2WnoEbr6E5hhP-VYI_YlaUUolH5y00kvBA5AYgR2BQyLSCO6QhG1i2Yv0_2Xv4w8PWoqfvcZg"

  val registerUserFeeder = Iterator.continually {
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

  val getDepartmentPayload: Expression[GetDepartmentReq] = GetDepartmentReq(id = "d1".asDepartmentId)
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
    .rpc(com.jc.user.api.proto.UserApiService.MethodDescriptors.registerUserDescriptor)
    .payload(registerUserPayload)

  val getDepartmentSuccessfulCall = grpc("getDepartment")
    .rpc(com.jc.user.api.proto.UserApiService.MethodDescriptors.getDepartmentDescriptor)
    .payload(getDepartmentPayload)
    .header(jwtAuthHeader)(jwtToken)

  val searchUsersSuccessfulCall = grpc("searchUsers")
    .rpc(com.jc.user.api.proto.UserApiService.MethodDescriptors.searchUsersDescriptor)
    .payload(searchUserPayload)

  val suggestUsersSuccessfulCall = grpc("suggestUsers")
    .rpc(com.jc.user.api.proto.UserApiService.MethodDescriptors.suggestUsersDescriptor)
    .payload(suggestUserPayload)

  val s = scenario("UserGrpcApi")
    .repeat(1) {
      feed(registerUserFeeder)
        .exec(registerUserSuccessfulCall)
        .feed(departmentIdFeeder)
        .exec(getDepartmentSuccessfulCall)
        .exec(searchUsersSuccessfulCall)
        .feed(countryFeeder)
        .exec(searchUsersSuccessfulCall)
        .feed(suggestFeeder)
        .exec(suggestUsersSuccessfulCall)
    }

  setUp(
    s.inject(atOnceUsers(200))
  ).protocols(grpcConf)
}
