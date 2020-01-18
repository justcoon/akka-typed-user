package c.cqrs

trait CommandProcessor[S, C[R] <: EntityCommand[_, _, R], E <: EntityEvent[_]] {
  def process(state: S, command: C[_]): List[E]
}

trait InitialCommandProcessor[C[R] <: EntityCommand[_, _, R], E <: EntityEvent[_]] {
  def process(command: C[_]): List[E]
}
