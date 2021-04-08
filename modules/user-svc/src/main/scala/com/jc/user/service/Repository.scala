package com.jc.user.service

import com.sksamuel.elastic4s.ElasticClient
import io.circe.{ Decoder, Encoder }
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.ClassTag

trait Repository[F[_], ID, E <: Repository.Entity[ID]] {

  def insert(value: E): F[Boolean]

  def update(value: E): F[Boolean]

  def delete(id: ID): F[Boolean]

  def find(id: ID): F[Option[E]]

  def findAll(): F[Seq[E]]
}

object Repository {
  trait Entity[ID] {
    def id: ID
  }
}

trait SearchRepository[F[_], E <: Repository.Entity[_]] {

  def search(
      query: Option[String],
      page: Int,
      pageSize: Int,
      sorts: Iterable[SearchRepository.FieldSort]
  ): F[Either[SearchRepository.SearchError, SearchRepository.PaginatedSequence[E]]]

  def suggest(
      query: String
  ): F[Either[SearchRepository.SuggestError, SearchRepository.SuggestResponse]]
}

object SearchRepository {

  final case class FieldSort(property: String, asc: Boolean)

  final case class PaginatedSequence[E](items: Seq[E], page: Int, pageSize: Int, count: Int)

  final case class SearchError(error: String)

  final case class SuggestError(error: String)

  final case class SuggestResponse(items: Seq[PropertySuggestions])

  final case class TermSuggestion(text: String, score: Double, freq: Int)

  final case class PropertySuggestions(property: String, suggestions: Seq[TermSuggestion])

}

class ESRepository[ID: Encoder: Decoder, E <: Repository.Entity[ID]: Encoder: Decoder: ClassTag](
    indexName: String,
    elasticClient: ElasticClient
)(implicit
    ec: ExecutionContext
) extends Repository[Future, ID, E] {

  import com.sksamuel.elastic4s.ElasticDsl.{ search => searchIndex, _ }
  import com.sksamuel.elastic4s.circe._

  protected val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def insert(value: E): Future[Boolean] = {
    val id = value.id.toString
    logger.debug("insert - {} - id: {}", indexName, id)

    elasticClient
      .execute {
        indexInto(indexName).doc(value).id(id)
      }
      .map(_.isSuccess)
      .recoverWith { case e =>
        logger.error("insert - {} - id: {} - error: {}", indexName, id, e.getMessage)
        Future.failed(e)
      }
  }

  override def update(value: E): Future[Boolean] = {
    val id = value.id.toString
    logger.debug("update - {} - id: {}", indexName, id)

    elasticClient
      .execute {
        updateById(indexName, id).doc(value)
      }
      .map(_.isSuccess)
      .recoverWith { case e =>
        logger.error("update - {} - id: {} - error: {}", indexName, id, e.getMessage)
        Future.failed(e)
      }
  }

  override def delete(id: ID): Future[Boolean] = {
    val idStr = id.toString
    logger.debug("delete - {} - id: {}", indexName, idStr)
    elasticClient
      .execute {
        deleteById(indexName, idStr)
      }
      .map(_.isSuccess)
      .recoverWith { case e =>
        logger.error("delete - {} - id: {} - error: {}", indexName, idStr, e.getMessage)
        Future.failed(e)
      }
  }

  override def find(id: ID): Future[Option[E]] = {
    val idStr = id.toString
    logger.debug("find - {} - id: {}", indexName, idStr)

    elasticClient
      .execute {
        get(indexName, idStr)
      }
      .map(r =>
        if (r.result.exists)
          Option(r.result.to[E])
        else
          Option.empty
      )
      .recoverWith { case e =>
        logger.error("find - {} - id: {} - error: {}", indexName, id, e.getMessage)
        Future.failed(e)
      }
  }

  override def findAll(): Future[Seq[E]] = {
    logger.debug("findAll - {}", indexName)

    elasticClient
      .execute {
        searchIndex(indexName).matchAllQuery()
      }
      .map(_.result.to[E])
      .recoverWith { case e =>
        logger.error("findAll - {} - error: {}", indexName, e.getMessage)
        Future.failed(e)
      }
  }
}

class ESSearchRepository[E <: Repository.Entity[_]: Encoder: Decoder: ClassTag](
    indexName: String,
    suggestProperties: Seq[String],
    elasticClient: ElasticClient
)(implicit
    ec: ExecutionContext
) extends SearchRepository[Future, E] {
  import com.sksamuel.elastic4s.ElasticDsl.{ search => searchIndex, _ }
  import com.sksamuel.elastic4s.circe._
  import com.sksamuel.elastic4s.requests.searches.queries.QueryStringQuery
  import com.sksamuel.elastic4s.requests.searches.queries.matches.MatchAllQuery
  import com.sksamuel.elastic4s.requests.searches.sort.{ FieldSort, SortOrder }
  import com.sksamuel.elastic4s.requests.searches.suggestion

  protected val logger: Logger = LoggerFactory.getLogger(this.getClass)

  override def search(
      query: Option[String],
      page: Int,
      pageSize: Int,
      sorts: Iterable[SearchRepository.FieldSort]
  ): Future[Either[SearchRepository.SearchError, SearchRepository.PaginatedSequence[E]]] = {

    val q = query.map(QueryStringQuery(_)).getOrElse(MatchAllQuery())
    val ss = sorts.map { case SearchRepository.FieldSort(property, asc) =>
      val o = if (asc) SortOrder.Asc else SortOrder.Desc
      FieldSort(property, order = o)
    }

    logger.debug(
      "search - {} - query: {}, page: {}, pageSize: {}, sorts: {}",
      indexName,
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
          val items = res.result.to[E]
          Right(SearchRepository.PaginatedSequence(items, page, pageSize, res.result.totalHits.toInt))
        } else {
          Left(SearchRepository.SearchError(ElasticUtils.getReason(res.error)))
        }
      }
      .recoverWith { case e =>
        logger.error(
          "search - {} - query: {}, page: {}, pageSize: {}, sorts: {} - error: {}",
          indexName,
          query.getOrElse("N/A"),
          page,
          pageSize,
          sorts.mkString("[", ",", "]"),
          e.getMessage
        )
        Future.successful(Left(SearchRepository.SearchError(e.getMessage)))
      }
  }

  override def suggest(query: String): Future[Either[SearchRepository.SuggestError, SearchRepository.SuggestResponse]] = {
    // completion suggestion
    val complSuggestions = suggestProperties.map { p =>
      suggestion
        .CompletionSuggestion(ElasticUtils.getSuggestPropertyName(p), ElasticUtils.getSuggestPropertyName(p))
        .prefix(query)
    }

    logger.debug("suggest - {} - query: {}", indexName, query)

    elasticClient
      .execute {
        searchIndex(indexName).suggestions(complSuggestions)
      }
      .map { res =>
        if (res.isSuccess) {
          val elasticSuggestions = res.result.suggestions
          val suggestions = suggestProperties.map { p =>
            val propertySuggestions = elasticSuggestions(ElasticUtils.getSuggestPropertyName(p))
            val suggestions = propertySuggestions.flatMap { v =>
              val t = v.toCompletion
              t.options.map(o => SearchRepository.TermSuggestion(o.text, o.score, o.score.toInt))
            }

            SearchRepository.PropertySuggestions(p, suggestions)
          }
          Right(SearchRepository.SuggestResponse(suggestions))
        } else {
          Left(SearchRepository.SuggestError(ElasticUtils.getReason(res.error)))
        }
      }
      .recoverWith { case e =>
        logger.error("suggest - {} - query: {} - error: {}", indexName, query, e.getMessage)
        Future.successful(Left(SearchRepository.SuggestError(e.getMessage)))
      }
  }
}
