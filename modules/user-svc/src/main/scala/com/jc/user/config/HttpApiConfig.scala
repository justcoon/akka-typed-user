package com.jc.user.config

import eu.timepit.refined.types.net.NonSystemPortNumber
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

case class HttpApiConfig(address: IpAddress, port: NonSystemPortNumber, repositoryTimeout: FiniteDuration)

object HttpApiConfig {
  import eu.timepit.refined.pureconfig._
  implicit lazy val configReader = deriveReader[HttpApiConfig]
}
