package com.jc.user

import akka.Done
import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.{ CassandraSession, CassandraSessionRegistry }

import scala.concurrent.{ ExecutionContext, Future }

object CassandraUtils {

  val CfgNamespaceDefault: String = "akka.persistence.cassandra"

  def get(cfgNamespace: String = CfgNamespaceDefault)(implicit system: ActorSystem[_]): CassandraSession =
    CassandraSessionRegistry.get(system).sessionFor(CassandraSessionSettings(cfgNamespace))

  def readCqlStatements(src: scala.io.Source): Seq[String] =
    src.mkString
      .split(";")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toList

  def executeStatements(statements: Seq[String], session: CassandraSession)(implicit ec: ExecutionContext): Future[Done] =
    Future
      .traverse(statements) { statement =>
        session.underlying().map(_.execute(statement))
      }
      .map(_ => Done)

}
