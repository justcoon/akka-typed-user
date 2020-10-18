package com.jc.user.config

import com.jc.auth.JwtConfig
import pureconfig.generic.semiauto.deriveReader

case class AppConfig(restApi: HttpApiConfig, grpcApi: HttpApiConfig, kafka: KafkaConfig, elasticsearch: ElasticsearchConfig, jwt: JwtConfig)

object AppConfig {
  implicit lazy val configReader = deriveReader[AppConfig]
}
