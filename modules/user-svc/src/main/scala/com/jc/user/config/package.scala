package com.jc.user

import akka.http.scaladsl.model.Uri
import eu.timepit.refined.api.{ Refined, Validate }
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.string.IPv4

package object config {

  type Addresses = List[String Refined HostPort] Refined NonEmpty
  type IpAddress = String Refined IPv4

  type TopicName = String Refined NonEmpty
  type IndexName = String Refined NonEmpty

  final case class HostPort()

  object HostPort {
    implicit def hostPortValidate: Validate.Plain[String, HostPort] =
      Validate.fromPartial(Uri.Authority.parse(_), "HostPort", HostPort())
  }

}
