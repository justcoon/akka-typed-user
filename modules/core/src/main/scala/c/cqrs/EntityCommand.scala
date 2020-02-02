package c.cqrs

trait EntityCommand[ID, S, R] {
  def entityId: ID
}
