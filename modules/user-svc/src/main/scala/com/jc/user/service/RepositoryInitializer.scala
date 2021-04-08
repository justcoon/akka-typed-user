package com.jc.user.service

import akka.actor.typed.ActorSystem
import com.jc.support.ClusterTask
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.requests.mappings.FieldDefinition
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }

trait RepositoryInitializer[F[_]] {
  def init(): F[Boolean]
}

object RepositoryInitializer {

  def init(initializers: RepositoryInitializer[Future]*)(implicit ec: ExecutionContext, system: ActorSystem[_]): Unit = {
    val taskFn = () => Future.traverse(initializers.toList)(i => i.init())
    ClusterTask.createSingleton("RepositoryInitializer", taskFn)
  }
}

final class ESRepositoryInitializer(indexName: String, fields: Seq[FieldDefinition], elasticClient: ElasticClient)(implicit
    ec: ExecutionContext
) extends RepositoryInitializer[Future] {

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
              createIndex(indexName).mapping(properties(fields))
            }
            .map(r => r.result.acknowledged)
        } else {
          logger.debug("init: {} - updating ...", indexName)
          elasticClient
            .execute {
              putMapping(indexName).fields(fields)
            }
            .map(r => r.result.acknowledged)
        }
      )
      .recoverWith { case e =>
        logger.error("init: {} - error: {}", indexName, e.getMessage)
        Future.failed(e)
      }
}
