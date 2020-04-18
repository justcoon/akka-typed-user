package com.jc.user.service

import com.jc.user.domain.UserEntity
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.requests.searches.queries.matches.MatchAllQuery
import com.sksamuel.elastic4s.requests.searches.queries.QueryStringQuery
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
  ): F[Either[UserRepository.SearchError, UserRepository.PaginatedSequence[UserRepository.User]]]
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

  object User {
    import shapeless._

    val usernameLens: Lens[User, String]         = lens[User].username
    val emailLens: Lens[User, String]            = lens[User].email
    val passLens: Lens[User, String]             = lens[User].pass
    val addressLens: Lens[User, Option[Address]] = lens[User].address
    val deletedLens: Lens[User, Boolean]         = lens[User].deleted

    val usernameEmailPassAddressLens = usernameLens ~ emailLens ~ passLens ~ addressLens
  }

  final case class PaginatedSequence[T](items: Seq[T], page: Int, pageSize: Int, count: Int)

  final case class SearchError(error: String)
}

final class UserESRepository(indexName: String, elasticClient: ElasticClient)(implicit ec: ExecutionContext)
    extends UserRepository[Future] {

  import com.sksamuel.elastic4s.ElasticDsl.{ update => updateIndex, search => searchIndex, _ }
  import com.sksamuel.elastic4s.circe._
  import io.circe.generic.auto._

  private val logger = LoggerFactory.getLogger(this.getClass)

  override def insert(user: UserRepository.User): Future[Boolean] = {
    logger.debug("insert - id: {}", user.id)

    elasticClient
      .execute {
        indexInto(indexName).doc(user).id(user.id)
      }
      .map(_.isSuccess)
      .recoverWith {
        case e =>
          logger.error("insert - id: {} - error: {}", user.id, e.getMessage)
          Future.failed(e)
      }
  }

  override def update(user: UserRepository.User): Future[Boolean] = {
    logger.debug("update - id: {}", user.id)

    elasticClient
      .execute {
        updateIndex(user.id).in(indexName).doc(user)
      }
      .map(_.isSuccess)
      .recoverWith {
        case e =>
          logger.error("update - id: {} - error: {}", user.id, e.getMessage)
          Future.failed(e)
      }
  }

  override def find(id: UserEntity.UserId): Future[Option[UserRepository.User]] = {
    logger.debug("find - id: {}", id)

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
      .recoverWith {
        case e =>
          logger.error("find - id: {} - error: {}", id, e.getMessage)
          Future.failed(e)
      }
  }

  override def findAll(): Future[Array[UserRepository.User]] = {
    logger.debug("findAll")

    elasticClient
      .execute {
        searchIndex(indexName).matchAllQuery
      }
      .map(_.result.to[UserRepository.User].toArray)
      .recoverWith {
        case e =>
          logger.error("findAll - error: {}", e.getMessage)
          Future.failed(e)
      }
  }

  override def search(
      query: Option[String],
      page: Int,
      pageSize: Int,
      sorts: Iterable[UserRepository.FieldSort]
  ): Future[Either[UserRepository.SearchError, UserRepository.PaginatedSequence[UserRepository.User]]] = {

    val q = query.map(QueryStringQuery(_)).getOrElse(MatchAllQuery())
    val ss = sorts.map {
      case (property, asc) =>
        val o = if (asc) SortOrder.Asc else SortOrder.Desc
        FieldSort(property, order = o)
    }

    logger.debug("search - query: {}, page: {}, pageSize: {}, sorts: {}", query, page, pageSize, sorts.mkString("[", ",", "]"))

    elasticClient
      .execute {
        searchIndex(indexName).query(q).from(page * pageSize).limit(pageSize).sortBy(ss)
      }
      .map { res =>
        if (res.isSuccess) {
          val items = res.result.to[UserRepository.User]
          Right(UserRepository.PaginatedSequence(items, page, pageSize, res.result.totalHits.toInt))
        } else {
          Left(UserRepository.SearchError(ElasticUtils.getReason(res.error)))
        }
      }
      .recoverWith {
        case e =>
          logger.error(
            "search - query: {}, page: {}, pageSize: {}, sorts: {} - error: {}",
            query,
            page,
            pageSize,
            sorts.mkString("[", ",", "]"),
            e.getMessage
          )
          Future.successful(Left(UserRepository.SearchError(e.getMessage)))
      }
  }
}
