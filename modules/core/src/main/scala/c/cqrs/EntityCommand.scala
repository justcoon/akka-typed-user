package c.cqrs

trait EntityCommand[ID, S, R] {
  def entityID: ID
  def initializedReply: S => R
  def uninitializedReply: R
}
