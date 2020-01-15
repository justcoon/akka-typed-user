package c.user.service

import c.user.domain.UserEntity
import com.sksamuel.elastic4s.ElasticClient

import scala.concurrent.{ ExecutionContext, Future }

trait UserRepository[F[_]] {

  def insert(user: UserEntity.User): F[Boolean]

  def update(user: UserEntity.User): F[Boolean]

  def find(id: UserEntity.UserId): F[Option[UserEntity.User]]

  def findAll(): F[Array[UserEntity.User]]
}

final class UserESRepository(elasticClient: ElasticClient)(implicit ec: ExecutionContext) extends UserRepository[Future] {

  import UserESRepository._
  import com.sksamuel.elastic4s.ElasticDsl.{ update => updateIndex, _ }
  import com.sksamuel.elastic4s.circe._
  import io.circe.generic.auto._

  override def insert(user: UserEntity.User): Future[Boolean] =
    elasticClient
      .execute {
        indexInto(ESIndexType).doc(user).id(user.id)
      }
      .map(_.isSuccess)

  override def update(user: UserEntity.User): Future[Boolean] =
    elasticClient
      .execute {
        updateIndex(user.id).in(ESIndexType).doc(user)
      }
      .map(_.isSuccess)

  override def find(id: UserEntity.UserId): Future[Option[UserEntity.User]] =
    elasticClient
      .execute {
        get(id).from(ESIndexType)
      }
      .map(r =>
        if (r.result.exists)
          Option(r.result.to[UserEntity.User])
        else
          Option.empty
      )

  override def findAll(): Future[Array[UserEntity.User]] =
    elasticClient
      .execute {
        search(ESIndexType).matchAllQuery
      }
      .map(_.result.to[UserEntity.User].toArray)
}

object UserESRepository {

  final val ESType      = "user"
  final val ESIndex     = "c-user"
  final val ESIndexType = s"${ESIndex}_${ESType}"
}

trait UserRepositoryInitializer[F[_]] {
  def init(): F[Boolean]
}

final class UserESRepositoryInitializer(elasticClient: ElasticClient)(implicit ec: ExecutionContext)
    extends UserRepositoryInitializer[Future] {

  import UserESRepository._
  import com.sksamuel.elastic4s.ElasticDsl._

  def init(): Future[Boolean] =
    elasticClient
      .execute {
        indexExists(ESIndexType)
      }
      .flatMap(r =>
        if (!r.result.exists)
          elasticClient
            .execute {
              createIndex(ESIndexType)
            }
            .map(r => r.result.acknowledged)
        else
          Future {
            false
          }
      )
}
