package com.jc.user.domain

import java.util.Locale

import akka.http.scaladsl.util.FastFuture
import com.jc.user.domain.proto._

import scala.concurrent.Future

trait AddressValidationService[F[_]] {
  def validate(address: Address): F[AddressValidationService.ValidationResult]
}

object AddressValidationService {
  sealed trait ValidationResult

  final case object ValidResult extends ValidationResult

  final case class NotValidResult(errors: List[String]) extends ValidationResult
}

case object SimpleAddressValidationService extends AddressValidationService[Future] {
  private val isoCountries = Locale.getISOCountries.toSet

  override def validate(address: Address): Future[AddressValidationService.ValidationResult] =
    FastFuture.successful {
      if (isoCountries.contains(address.country)) AddressValidationService.ValidResult
      else AddressValidationService.NotValidResult(List(s"country: ${address.country} is not valid ISO country"))
    }
}
