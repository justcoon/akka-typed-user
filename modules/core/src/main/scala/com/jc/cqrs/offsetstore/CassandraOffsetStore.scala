package com.jc.cqrs.offsetstore

import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import com.jc.support.ClusterTask

object CassandraOffsetStore {
  val AlpakkaCassandraConfigPath: String = "alpakka.cassandra"
  val JournalKeyspaceConfigPath: String  = "akka.persistence.cassandra.journal.keyspace"

  def createOffsetTableStmt(keyspace: String) =
    s"""
      CREATE TABLE IF NOT EXISTS ${keyspace}.offset_store (
        name text,
        time_uuid_offset timeuuid,
        PRIMARY KEY (name)
      )
      """

  def createSelectOffsetStmt(keyspace: String) =
    s"SELECT time_uuid_offset FROM ${keyspace}.offset_store WHERE name = ?"

  def createInsertOffsetStmt(keyspace: String) =
    s"INSERT INTO ${keyspace}.offset_store (name, time_uuid_offset) VALUES (?, ?)"

  def createSession()(implicit system: ActorSystem[_]) =
    CassandraSessionRegistry(system).sessionFor(AlpakkaCassandraConfigPath)

  def init(keyspace: String)(implicit system: ActorSystem[_]): Unit = {
    val session = createSession()
    val stmt    = createOffsetTableStmt(keyspace)
    ClusterTask.createSingleton("CassandraOffsetStoreInitializer", () => session.executeDDL(stmt))
  }
}
