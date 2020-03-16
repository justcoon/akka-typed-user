package com.jc.user.config

import pureconfig.generic.semiauto.deriveReader

case class AppConfig(restApi: HttpApiConfig, grpcApi: HttpApiConfig, kafka: KafkaConfig, elasticsearch: ElasticsearchConfig)

object AppConfig {
  implicit lazy val configReader = deriveReader[AppConfig]
}
