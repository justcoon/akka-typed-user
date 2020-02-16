package c.user.service

import akka.actor.typed.ActorSystem
import c.support.ClusterTask
import c.user.domain.UserEntity
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.requests.searches.queries.matches.MatchAllQuery
import com.sksamuel.elastic4s.requests.searches.queries.{ NoopQuery, QueryStringQuery }
import com.sksamuel.elastic4s.requests.searches.sort.{ FieldSort, SortOrder }
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }

trait UserRepository[F[_]] {

  def insert(user: UserRepository.User): F[Boolean]

  def update(user: UserRepository.User): F[Boolean]

  def find(id: UserEntity.UserId): F[Option[UserRepository.User]]

  def findAll(): F[Array[UserRepository.User]]

  def search(
      query: Option[String],
      page: Int,
      pageSize: Int,
      sorts: Iterable[UserRepository.FieldSort]
  ): F[UserRepository.PaginatedSequence[UserRepository.User]]
}

object UserRepository {
  type FieldSort = (String, Boolean)

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

  final case class PaginatedSequence[T](items: Seq[T], page: Int, pageSize: Int, count: Int)
}

final class UserESRepository(indexName: String, elasticClient: ElasticClient)(implicit ec: ExecutionContext)
    extends UserRepository[Future] {

  import com.sksamuel.elastic4s.ElasticDsl.{ update => updateIndex, search => searchIndex, _ }
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
        searchIndex(indexName).matchAllQuery
      }
      .map(_.result.to[UserRepository.User].toArray)

  override def search(
      query: Option[String],
      page: Int,
      pageSize: Int,
      sorts: Iterable[UserRepository.FieldSort]
  ): Future[UserRepository.PaginatedSequence[UserRepository.User]] = {
    val q = query.map(QueryStringQuery(_)).getOrElse(MatchAllQuery())
    val ss = sorts.map {
      case (property, asc) =>
        val o = if (asc) SortOrder.Asc else SortOrder.Desc
        FieldSort(property, order = o)
    }
    elasticClient
      .execute {
        searchIndex(indexName).query(q).from(page * pageSize).limit(pageSize).sortBy(ss)
      }
      .map { res =>
        val items = res.result.to[UserRepository.User]
        UserRepository.PaginatedSequence(items, page, pageSize, res.result.totalHits.toInt)
      }
  }
}

trait UserRepositoryInitializer[F[_]] {
  def init(): F[Boolean]
}

final class UserESRepositoryInitializer(indexName: String, elasticClient: ElasticClient)(implicit ec: ExecutionContext)
    extends UserRepositoryInitializer[Future] {

  import com.sksamuel.elastic4s.ElasticDsl._

  private val logger = LoggerFactory.getLogger(this.getClass)

  def init(): Future[Boolean] =
    elasticClient
      .execute {
        indexExists(indexName)
      }
      .flatMap(r =>
        if (!r.result.exists) {
          logger.debug("init: {} - initializing ...", indexName)
          elasticClient
            .execute {
              createIndex(indexName).mapping(properties(UserESRepositoryInitializer.fields))
            }
            .map(r => r.result.acknowledged)
        } else {
          logger.debug("init: {} - updating ...", indexName)
          elasticClient
            .execute {
              putMapping(indexName).fields(UserESRepositoryInitializer.fields)
            }
            .map(r => r.result.acknowledged)
        }
      )
}

object UserESRepositoryInitializer {
  import com.sksamuel.elastic4s.ElasticDsl._

  val fields = Seq(
    textField("id").fielddata(true),
    textField("username").fielddata(true),
    textField("email").fielddata(true),
    textField("address.street").fielddata(true),
    textField("address.number").fielddata(true),
    textField("address.city").fielddata(true),
    textField("address.state").fielddata(true),
    textField("address.zip").fielddata(true),
    textField("address.country").fielddata(true),
    booleanField("deleted")
  )

  def init(indexName: String, elasticClient: ElasticClient)(implicit ec: ExecutionContext, system: ActorSystem[_]): Unit = {
    val userRepositoryInitializer = new UserESRepositoryInitializer(indexName, elasticClient)
    ClusterTask.createSingleton("UserESRepositoryInitializer", () => userRepositoryInitializer.init())
  }
}
