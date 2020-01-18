package c.cqrs

trait EntityEvent[ID] {
  def entityID: ID
  def timestamp: java.time.Instant
}
