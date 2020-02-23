package com.jc.user.service

import akka.actor.typed.ActorSystem
import com.jc.support.ClusterTask
import com.sksamuel.elastic4s.ElasticClient
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }

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
