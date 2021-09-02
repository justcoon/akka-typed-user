package com.jc.auth

import java.time.Clock

import akka.http.scaladsl.model.StatusCodes.Unauthorized
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.{ complete, optionalHeaderValueByName, provide }
import eu.timepit.refined.types.numeric.PosLong
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{ Decoder, Encoder }
import pdi.jwt._
import pureconfig.generic.semiauto.deriveReader

import scala.util.Try

final case class JwtConfig(secret: NonEmptyString, expiration: PosLong, issuer: Option[NonEmptyString] = None)

object JwtConfig {
  import eu.timepit.refined.pureconfig._
  implicit lazy val configReader = deriveReader[JwtConfig]
}

trait JwtAuthenticator[S] {
  def authenticated(rawToken: String): Option[S]
}

object JwtAuthenticator {
  val AuthHeader        = "Authorization"
  val BearerTokenPrefix = "Bearer "

  def sanitizeBearerAuthToken(header: String): String = header.replaceFirst(BearerTokenPrefix, "")
}

class PdiJwtAuthenticator(val helper: PdiJwtHelper, val clock: Clock) extends JwtAuthenticator[String] {
  override def authenticated(rawToken: String): Option[String] =
    for {
      claim <- helper.decodeClaim(rawToken).toOption
      subject <-
        if (claim.isValid(clock)) {
          claim.subject
        } else None
    } yield subject

}

object PdiJwtAuthenticator {

  def create(config: JwtConfig, clock: Clock): PdiJwtAuthenticator =
    new PdiJwtAuthenticator(new PdiJwtHelper(config), clock)

  def jwtAuthenticatedDirective(authenticator: PdiJwtAuthenticator): Directive1[Map[String, Any]] =
    optionalHeaderValueByName(JwtAuthenticator.AuthHeader)
      .flatMap { ah =>
        val jc = ah.flatMap(rt => authenticator.helper.decodeClaim(rt).toOption)

        jc match {
          case Some(jwt) if !jwt.isValid(authenticator.clock) =>
            complete(Unauthorized -> "Token expired.")

          case Some(jwt) =>
            provide(Map("sub" -> jwt.subject.getOrElse(""), "exp" -> jwt.expiration.getOrElse("")))

          case _ => complete(Unauthorized)
        }
      }

}

final class PdiJwtHelper(val config: JwtConfig) {
  import eu.timepit.refined.auto._

  def claim[T: Encoder](
      content: T,
      issuer: Option[String] = None,
      subject: Option[String] = None,
      audience: Option[Set[String]] = None
  )(implicit clock: Clock): JwtClaim = {
    import io.circe.syntax._
    val jsContent = content.asJson.noSpaces

    JwtClaim(content = jsContent, issuer = issuer.orElse(config.issuer.map(_.value)), subject = subject, audience = audience).issuedNow
      .expiresIn(config.expiration)
  }

  def encodeClaim(claim: JwtClaim): String =
    JwtCirce.encode(claim, config.secret, PdiJwtHelper.Algorithm)

  def encode[T: Encoder](
      content: T,
      issuer: Option[String] = None,
      subject: Option[String] = None,
      audience: Option[Set[String]] = None
  )(implicit clock: Clock): String =
    encodeClaim(claim(content, issuer, subject, audience))

  def decodeClaim(rawToken: String): Try[JwtClaim] =
    JwtCirce.decode(rawToken, config.secret, Seq(PdiJwtHelper.Algorithm), JwtOptions(expiration = false))

  def decode[T: Decoder](rawToken: String): Try[(JwtClaim, T, String)] =
    for {
      claim   <- decodeClaim(rawToken)
      content <- io.circe.parser.decode[T](claim.content).toTry
    } yield (claim, content, rawToken)

}

object PdiJwtHelper {
  val Algorithm = JwtAlgorithm.HS512
}
