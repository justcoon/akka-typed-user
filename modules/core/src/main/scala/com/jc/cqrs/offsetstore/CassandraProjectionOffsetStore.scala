package com.jc.cqrs.offsetstore

import akka.actor.typed.ActorSystem
import akka.projection.cassandra.scaladsl.CassandraProjection
import com.jc.support.ClusterTask

object CassandraProjectionOffsetStore {
  def init()(implicit system: ActorSystem[_]): Unit =
    ClusterTask.createSingleton("CassandraProjectionOffsetStoreInitializer", () => CassandraProjection.createOffsetTableIfNotExists())
}
