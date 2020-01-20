package c.cqrs.offsetstore

trait OffsetStore[O, F[_]] {

  def loadOffset(name: String): F[Option[O]]

  def storeOffset(name: String, offset: O): F[O]

}