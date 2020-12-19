package com.jc.user.domain

import java.util.Locale
import akka.http.scaladsl.util.FastFuture
import cats.data.{ Validated, ValidatedNec }
import com.jc.user.domain.proto._

import scala.concurrent.Future

trait AddressValidationService[F[_]] {
  def validate(address: Address): F[ValidatedNec[String, Address]]
}

final case object SimpleAddressValidationService extends AddressValidationService[Future] {
  private val isoCountries = Locale.getISOCountries.toSet

  override def validate(address: Address): Future[ValidatedNec[String, Address]] = {
    import cats.implicits._
    FastFuture.successful {
      if (isoCountries.contains(address.country)) address.validNec
      else s"Country: ${address.country} is not valid ISO country".invalidNec
    }
  }
}
