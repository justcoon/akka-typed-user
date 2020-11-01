package com.jc.user.service

import com.jc.user.domain.UserEntity
import com.sksamuel.elastic4s.ElasticClient
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }

trait UserRepository[F[_]] {

  def insert(user: UserRepository.User): F[Boolean]

  def update(user: UserRepository.User): F[Boolean]

  def find(id: UserEntity.UserId): F[Option[UserRepository.User]]

  def findAll(): F[Seq[UserRepository.User]]

  def search(
      query: Option[String],
      page: Int,
      pageSize: Int,
      sorts: Iterable[UserRepository.FieldSort]
  ): F[Either[UserRepository.SearchError, UserRepository.PaginatedSequence[UserRepository.User]]]

  def suggest(
      query: String
  ): F[Either[UserRepository.SuggestError, UserRepository.SuggestResponse]]
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
  object Address {
    import io.circe._, io.circe.generic.semiauto._

    implicit val addressDecoder: Decoder[Address] = deriveDecoder[Address]

    implicit val addressEncoder: Encoder[Address] = deriveEncoder[Address]
  }

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

    val usernameEmailPassAddressLens: ProductLensBuilder[User, (String, String, String, Option[Address])] =
      usernameLens ~ emailLens ~ passLens ~ addressLens

    import io.circe._, io.circe.generic.semiauto._

    implicit val userDecoder: Decoder[User] = deriveDecoder[User]

    implicit val userEncoder: Encoder[User] = new Encoder[User] {

      val derived: Encoder[User] = deriveEncoder[User]

      override def apply(a: User): Json =
        derived(a).mapObject { jo =>
          jo.add(ElasticUtils.getSuggestPropertyName("username"), Json.fromString(a.username))
            .add(ElasticUtils.getSuggestPropertyName("email"), Json.fromString(a.email))
        }
    }
  }

  final case class PaginatedSequence[T](items: Seq[T], page: Int, pageSize: Int, count: Int)

  final case class SearchError(error: String)

  final case class SuggestError(error: String)

  final case class SuggestResponse(items: Seq[PropertySuggestions])

  final case class TermSuggestion(text: String, score: Double, freq: Int)

  final case class PropertySuggestions(property: String, suggestions: Seq[TermSuggestion])

}

final class UserESRepository(indexName: String, elasticClient: ElasticClient)(implicit ec: ExecutionContext)
    extends UserRepository[Future] {

  import com.sksamuel.elastic4s.ElasticDsl.{ search => searchIndex, _ }
  import com.sksamuel.elastic4s.circe._
  import com.sksamuel.elastic4s.requests.searches.queries.matches.MatchAllQuery
  import com.sksamuel.elastic4s.requests.searches.queries.QueryStringQuery
  import com.sksamuel.elastic4s.requests.searches.sort.{ FieldSort, SortOrder }
  import com.sksamuel.elastic4s.requests.searches.suggestion
  import UserRepository.User._

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
        updateById(indexName, user.id).doc(user)
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
        get(indexName, id)
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

  override def findAll(): Future[Seq[UserRepository.User]] = {
    logger.debug("findAll")

    elasticClient
      .execute {
        searchIndex(indexName).matchAllQuery()
      }
      .map(_.result.to[UserRepository.User])
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

    logger.debug(
      "search - query: {}, page: {}, pageSize: {}, sorts: {}",
      query.getOrElse("N/A"),
      page,
      pageSize,
      sorts.mkString("[", ",", "]")
    )

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
            query.getOrElse("N/A"),
            page,
            pageSize,
            sorts.mkString("[", ",", "]"),
            e.getMessage
          )
          Future.successful(Left(UserRepository.SearchError(e.getMessage)))
      }
  }

  override def suggest(query: String): Future[Either[UserRepository.SuggestError, UserRepository.SuggestResponse]] = {
    // completion suggestion
    val complSuggestions = UserESRepositoryInitializer.suggestProperties.map { p =>
      suggestion
        .CompletionSuggestion(ElasticUtils.getSuggestPropertyName(p), ElasticUtils.getSuggestPropertyName(p))
        .prefix(query)
    }

    logger.debug("suggest - query: {}", query)

    elasticClient
      .execute {
        searchIndex(indexName).suggestions(complSuggestions)
      }
      .map { res =>
        if (res.isSuccess) {
          val elasticSuggestions = res.result.suggestions
          val suggestions = UserESRepositoryInitializer.suggestProperties.map { p =>
            val propertySuggestions = elasticSuggestions(ElasticUtils.getSuggestPropertyName(p))
            val suggestions = propertySuggestions.flatMap { v =>
              val t = v.toCompletion
              t.options.map(o => UserRepository.TermSuggestion(o.text, o.score, o.score.toInt))
            }

            UserRepository.PropertySuggestions(p, suggestions)
          }
          Right(UserRepository.SuggestResponse(suggestions))
        } else {
          Left(UserRepository.SuggestError(ElasticUtils.getReason(res.error)))
        }
      }
      .recoverWith {
        case e =>
          logger.error("suggest - query: {} - error: {}", query, e.getMessage)
          Future.successful(Left(UserRepository.SuggestError(e.getMessage)))
      }
  }

//  override def suggest(query: String): Future[Either[UserRepository.SuggestError, UserRepository.SuggestResponse]] = {
//    // term suggestion
//    val termSuggestions = UserESRepositoryInitializer.suggestProperties.map { p =>
//      suggestion
//        .TermSuggestion(ElasticUtils.getTermSuggestionName(p), p, Some(query))
//        .mode(suggestion.SuggestMode.Always)
//    }
//
//    logger.debug("suggest - query: {}", query)
//
//    elasticClient
//      .execute {
//        searchIndex(indexName).suggestions(termSuggestions)
//      }
//      .map { res =>
//        if (res.isSuccess) {
//          val elasticSuggestions = res.result.suggestions
//          val suggestions = UserESRepositoryInitializer.suggestProperties.map { p =>
//            val propertySuggestions = elasticSuggestions(ElasticUtils.getTermSuggestionName(p))
//            val suggestions = propertySuggestions.flatMap { v =>
//              val t = v.toTerm
//              t.options.map { o =>
//                UserRepository.TermSuggestion(o.text, o.score, o.freq)
//              }
//            }
//
//            UserRepository.PropertySuggestions(p, suggestions)
//          }
//          Right(UserRepository.SuggestResponse(suggestions))
//        } else {
//          Left(UserRepository.SuggestError(ElasticUtils.getReason(res.error)))
//        }
//      }
//      .recoverWith {
//        case e =>
//          logger.error("suggest - query: {} - error: {}", query, e.getMessage)
//          Future.successful(Left(UserRepository.SuggestError(e.getMessage)))
//      }
//  }

}
