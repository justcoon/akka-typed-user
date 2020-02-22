package com.jc.auth

import java.time.Clock

import akka.http.scaladsl.model.StatusCodes.Unauthorized
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.{ complete, optionalHeaderValueByName, provide }
import pdi.jwt._

object jwt {

  case class JwtConfig(secret: String, expiration: Long)

  implicit val clock: Clock = Clock.systemUTC
  val jwtAuthHeader         = "Authorization"
  val jwtAlgorithm          = JwtAlgorithm.HS512
  //  val jwtClaimCreated = "created"
  //  val jwtClaimUsername = "sub"

  def jwtAuthenticatedDirective(jwtSecret: String, jwtExpiration: Long): Directive1[Map[String, Any]] =
    optionalHeaderValueByName(jwtAuthHeader)
      .flatMap { ah =>
        val jc = ah.flatMap(rt => getJwtClaim(rt, jwtSecret))

        jc match {
          case Some(jwt) if isJwtClaimExpired(jwt, jwtExpiration) =>
            complete(Unauthorized -> "Token expired.")

          case Some(jwt) if jwt.isValid =>
            provide(Map("sub" -> jwt.subject.getOrElse(""), "exp" -> jwt.expiration.getOrElse("")))

          case _ => complete(Unauthorized)
        }
      }

  def jwtAuthenticatedDirective(jwtConfig: JwtConfig): Directive1[Map[String, Any]] =
    jwtAuthenticatedDirective(jwtConfig.secret, jwtConfig.expiration)

  def getJwtClaim(rawToken: String, jwtSecret: String) = {
    val c = JwtCirce.decode(rawToken, jwtSecret, Seq(jwtAlgorithm), JwtOptions(expiration = false))
    c.toOption
  }

  def isJwtClaimExpired(jwtClaim: JwtClaim, jwtExpiration: Long) =
    jwtClaim.expiration match {
      case Some(d) =>
        jwtExpiration <= 0 || (d * 1000) < System.currentTimeMillis()
      case None => true
    }
}
