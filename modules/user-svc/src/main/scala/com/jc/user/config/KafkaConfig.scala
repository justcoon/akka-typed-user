package com.jc.user.config

import pureconfig.generic.semiauto.deriveReader

case class KafkaConfig(userTopic: TopicName, departmentTopic: TopicName)

object KafkaConfig {
  import eu.timepit.refined.pureconfig._
  implicit lazy val configReader = deriveReader[KafkaConfig]
}
