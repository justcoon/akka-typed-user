package c.cqrs

trait EntityCommand[ID, S, R] {
  def entityId: ID
  def initializedReply: S => R
  def uninitializedReply: R
}
