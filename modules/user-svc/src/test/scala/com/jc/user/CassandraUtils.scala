package com.jc.user

import akka.Done
import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.scaladsl.{ CassandraSession, CassandraSessionRegistry }
import com.datastax.oss.driver.api.core.CqlSession

import scala.concurrent.{ ExecutionContext, Future }

object CassandraUtils {

  val CfgNamespaceDefault: String = "akka.persistence.cassandra"

  def get(cfgNamespace: String = CfgNamespaceDefault)(implicit system: ActorSystem[_]): CassandraSession =
    CassandraSessionRegistry.get(system).sessionFor(cfgNamespace)

  def init(statements: Seq[String], cfgNamespace: String = CfgNamespaceDefault)(implicit
      system: ActorSystem[_],
      ec: ExecutionContext
  ): Future[Done] = {
    val session = CassandraSessionRegistry.get(system).sessionFor(cfgNamespace, s => executeStatements(statements, s))
    session.close(ec)
  }

  def readCqlStatements(src: scala.io.Source): Seq[String] =
    src.mkString
      .split(";")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

  def executeStatements(statements: Seq[String], session: CqlSession)(implicit ec: ExecutionContext): Future[Done] =
    Future {
      statements.foreach(statement => session.execute(statement))
      Done
    }

}
