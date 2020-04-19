package com.jc.cqrs.offsetstore

import akka.actor.typed.ActorSystem
import akka.persistence.query.{ Offset, TimeBasedUUID }
import com.datastax.oss.driver.api.core.cql.Row
import org.slf4j.LoggerFactory

import scala.concurrent.{ ExecutionContext, Future }

class CassandraOffsetStoreService(keyspace: String)(
    implicit actorSystem: ActorSystem[_],
    executionContext: ExecutionContext
) extends OffsetStore[Offset, Future] {
  private val log     = LoggerFactory.getLogger(this.getClass)
  private val session = CassandraOffsetStore.createSession()

  private val selectOffsetStmt = CassandraOffsetStore.createSelectOffsetStmt(keyspace)
  private val insertOffsetStmt = CassandraOffsetStore.createInsertOffsetStmt(keyspace)

  override def loadOffset(name: String): Future[Option[Offset]] = {
    log.debug("loadOffset - name: {}", name)

    session
      .selectOne(selectOffsetStmt, name)
      .map(extractOffset)
  }

  override def storeOffset(name: String, offset: Offset): Future[Offset] =
    offset match {
      case t: TimeBasedUUID =>
        log.debug("storeOffset - name: {}, offset: {}", name, t.value)

        session.executeWrite(insertOffsetStmt, name, t.value).map(_ => offset)

      case _ =>
        Future.failed(new IllegalArgumentException(s"Unexpected offset type: $offset"))
    }

  private def extractOffset(maybeRow: Option[Row]): Option[Offset] =
    maybeRow.flatMap { row =>
      val uuid = row.getUuid(0)
      if (uuid == null)
        None
      else
        Some(TimeBasedUUID(uuid))
    }

}
