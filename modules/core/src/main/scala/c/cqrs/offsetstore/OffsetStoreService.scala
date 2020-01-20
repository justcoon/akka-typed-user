package c.cqrs.offsetstore

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.persistence.query.Offset
import akka.util.Timeout
import c.cqrs.TypedActorEntityService
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }

class OffsetStoreService()(
    implicit val sharding: ClusterSharding,
    val actorSystem: ActorSystem[_],
    val askTimeout: Timeout
) extends TypedActorEntityService[
      OffsetStoreEntity.OffsetStoreId,
      proto.OffsetStore,
      OffsetStoreEntity.OffsetStoreCommand,
      OffsetStorePersistentEntity
    ]
    with OffsetStore[Offset, Future] {
  implicit val executionContext: ExecutionContext = actorSystem.executionContext

  val log = LoggerFactory.getLogger(this.getClass)

  lazy val persistentEntity = OffsetStorePersistentEntity()

  override def loadOffset(name: String): Future[Option[Offset]] = {
    import OffsetStoreEntity._
    val id = name.asOffsetStoreId
    log.debug("loadOffset - name: {}", name)
    sendCommand(OffsetStoreEntity.GetOffsetStoreCommand(id)).map {
      case OffsetStoreReply(os) => Some(os.offset)
      case _                    => None
    }
  }

  override def storeOffset(name: String, offset: Offset): Future[Offset] = {
    import OffsetStoreEntity._
    val id = name.asOffsetStoreId
    log.debug("storeOffset - name: {}, offset: {}", name, offset)
    sendCommand(OffsetStoreEntity.CreateOrUpdateOffsetStoreCommand(id, offset)).map { _ =>
      offset
    }
  }
}