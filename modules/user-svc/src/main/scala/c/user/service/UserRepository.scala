package c.user.service

import akka.actor.typed.ActorSystem
import c.cqrs.ClusterTask
import c.user.domain.UserEntity
import com.sksamuel.elastic4s.ElasticClient

import scala.concurrent.{ ExecutionContext, Future }

trait UserRepository[F[_]] {

  def insert(user: UserRepository.User): F[Boolean]

  def update(user: UserRepository.User): F[Boolean]

  def find(id: UserEntity.UserId): F[Option[UserRepository.User]]

  def findAll(): F[Array[UserRepository.User]]
}

object UserRepository {

  final case class Address(
      street: String,
      number: String,
      zip: String,
      city: String,
      state: String,
      country: String
  )

  final case class User(
      id: UserEntity.UserId,
      username: String,
      email: String,
      pass: String,
      address: Option[Address] = None,
      deleted: Boolean = false
  )

}

final class UserESRepository(indexName: String, elasticClient: ElasticClient)(implicit ec: ExecutionContext)
    extends UserRepository[Future] {

  import com.sksamuel.elastic4s.ElasticDsl.{ update => updateIndex, _ }
  import com.sksamuel.elastic4s.circe._
  import io.circe.generic.auto._

  override def insert(user: UserRepository.User): Future[Boolean] =
    elasticClient
      .execute {
        indexInto(indexName).doc(user).id(user.id)
      }
      .map(_.isSuccess)

  override def update(user: UserRepository.User): Future[Boolean] =
    elasticClient
      .execute {
        updateIndex(user.id).in(indexName).doc(user)
      }
      .map(_.isSuccess)

  override def find(id: UserEntity.UserId): Future[Option[UserRepository.User]] =
    elasticClient
      .execute {
        get(id).from(indexName)
      }
      .map(r =>
        if (r.result.exists)
          Option(r.result.to[UserRepository.User])
        else
          Option.empty
      )

  override def findAll(): Future[Array[UserRepository.User]] =
    elasticClient
      .execute {
        search(indexName).matchAllQuery
      }
      .map(_.result.to[UserRepository.User].toArray)
}

trait UserRepositoryInitializer[F[_]] {
  def init(): F[Boolean]
}

final class UserESRepositoryInitializer(indexName: String, elasticClient: ElasticClient)(implicit ec: ExecutionContext)
    extends UserRepositoryInitializer[Future] {

  import com.sksamuel.elastic4s.ElasticDsl._

  def init(): Future[Boolean] =
    elasticClient
      .execute {
        indexExists(indexName)
      }
      .flatMap(r =>
        if (!r.result.exists)
          elasticClient
            .execute {
              createIndex(indexName)
            }
            .map(r => r.result.acknowledged)
        else
          Future {
            false
          }
      )
}

object UserESRepositoryInitializer {

  def init(indexName: String, elasticClient: ElasticClient)(implicit ec: ExecutionContext, system: ActorSystem[_]): Unit = {
    val userRepositoryInitializer = new UserESRepositoryInitializer(indexName, elasticClient)
    ClusterTask.create("UserESRepositoryInitializer", () => userRepositoryInitializer.init())
  }
}
