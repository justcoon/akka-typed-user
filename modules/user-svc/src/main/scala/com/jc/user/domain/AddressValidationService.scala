package com.jc.user.domain

import java.util.Locale
import akka.http.scaladsl.util.FastFuture
import cats.data.ValidatedNec
import com.jc.user.domain.proto._

import scala.concurrent.Future

trait AddressValidationService[F[_]] {
  def validate(address: Address): F[ValidatedNec[String, Address]]
}

final case object SimpleAddressValidationService extends AddressValidationService[Future] {
  import cats.implicits._
  private val isoCountries = Locale.getISOCountries.toSet
  private val zipRegex     = "^\\d{5}(?:[-\\s]\\d{4})?$".r

  def validateCountry(country: String): ValidatedNec[String, String] =
    if (isoCountries.contains(country)) country.validNec
    else s"Country: ${country} is not valid ISO country".invalidNec

  def validateZip(zip: String): ValidatedNec[String, String] =
    if (zipRegex.matches(zip)) zip.validNec
    else s"Zip: ${zip} is not valid".invalidNec

  override def validate(address: Address): Future[ValidatedNec[String, Address]] =
    FastFuture.successful {
      validateZip(address.zip).combine(validateCountry(address.country)).map(_ => address)
    }
}
