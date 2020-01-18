package c.cqrs

import scala.reflect.ClassTag

sealed trait EntityEventTagger[E <: EntityEvent[_]] {
  def tags(event: E): Set[String]

  def allTags: Set[String]
}

final class ShardedEntityEventTagger[E <: EntityEvent[_]](
    val eventType: Class[E],
    val baseTag: String,
    val numShards: Int
) extends EntityEventTagger[E] {

  override def tags(event: E): Set[String] = {
    val shardNo  = ShardedEntityEventTagger.getShardNo(numShards, event)
    val shardTag = ShardedEntityEventTagger.shardTag(baseTag, shardNo)
    Set(shardTag)
  }

  override val allTags: Set[String] = ShardedEntityEventTagger.allShardTags(baseTag, numShards)

  override def toString: String = s"ShardedEntityEventTagger($eventType, $baseTag)"

  override def equals(other: Any): Boolean = other match {
    case that: ShardedEntityEventTagger[_] => baseTag == that.baseTag
    case _                                 => false
  }

  override def hashCode(): Int = baseTag.hashCode

}

object ShardedEntityEventTagger {

  def sharded[E <: EntityEvent[_]: ClassTag](
      numShards: Int
  ): ShardedEntityEventTagger[E] = {
    val eventType = implicitly[ClassTag[E]].runtimeClass.asInstanceOf[Class[E]]
    sharded[E](eventType.getSimpleName, numShards)
  }

  def sharded[E <: EntityEvent[_]: ClassTag](
      baseTagName: String,
      numShards: Int
  ): ShardedEntityEventTagger[E] =
    new ShardedEntityEventTagger[E](
      implicitly[ClassTag[E]].runtimeClass.asInstanceOf[Class[E]],
      baseTagName,
      numShards
    )

  def getShardNo[E <: EntityEvent[_]](numShards: Int, event: E): Int =
    Math.abs(event.entityID.hashCode) % numShards

  def shardTag(baseTag: String, shardNo: Int): String =
    s"$baseTag$shardNo"

  def allShardTags(baseTag: String, numShards: Int): Set[String] =
    (for (shardNo <- 0 until numShards)
      yield shardTag(baseTag, shardNo)).toSet
}
