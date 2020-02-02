package c.cqrs

trait CommandProcessor[S, C[R] <: EntityCommand[_, _, R], E <: EntityEvent[_]] {

  def process(state: S, command: C[_]): CommandProcessResult[E]
}

trait InitialCommandProcessor[C[R] <: EntityCommand[_, _, R], E <: EntityEvent[_]] {
  case class Result(events: List[E], reply: CommandReply[_])

  def process(command: C[_]): CommandProcessResult[E]
}

case class CommandProcessResult[E <: EntityEvent[_]](events: List[E], reply: CommandReply[_])

sealed trait CommandReply[+R]

object CommandReply {

  final case object NoReply extends CommandReply[Nothing]

  final case class Reply[R](reply: R) extends CommandReply[R]
}
