package com.jc.user.domain

import java.util.Locale

import akka.http.scaladsl.util.FastFuture
import com.jc.user.domain.proto._

import scala.concurrent.Future

trait AddressValidator[F[_]] {
  def validate(address: Address): F[AddressValidator.ValidationResult]
}

object AddressValidator {
  sealed trait ValidationResult

  final case object ValidResult extends ValidationResult

  final case class NotValidResult(errors: List[String]) extends ValidationResult
}

class SimpleAddressValidator extends AddressValidator[Future] {
  private val isoCountries = Locale.getISOCountries.toSet
  override def validate(address: Address): Future[AddressValidator.ValidationResult] =
    FastFuture.successful {
      if (isoCountries.contains(address.country)) AddressValidator.ValidResult
      else AddressValidator.NotValidResult(List(s"country: ${address.country} is not valid ISO country"))
    }

}
