package c.cqrs

trait OffsetStore[O, F[_]] {

  def loadOffset(name: String): F[Option[O]]

  def storeOffset(name: String, offset: O): F[O]

}
