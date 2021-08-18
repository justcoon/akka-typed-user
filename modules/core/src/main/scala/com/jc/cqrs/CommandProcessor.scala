package com.jc.cqrs

trait CommandProcessor[S, C[R] <: EntityCommand[_, _, R], E <: EntityEvent[_]] {

  def process(state: S, command: C[_]): CommandProcessResult[E, command.Reply]
}

trait InitialCommandProcessor[C[R] <: EntityCommand[_, _, R], E <: EntityEvent[_]] {

  def process(command: C[_]): CommandProcessResult[E, command.Reply]
}

final case class CommandProcessResult[E <: EntityEvent[_], +R](events: List[E], reply: CommandReply[R])

object CommandProcessResult {

  def withCmdReply[E <: EntityEvent[_], C <: EntityCommand[_, _, _]](command: C)(
      reply: command.Reply
  ): CommandProcessResult[E, command.Reply] =
    CommandProcessResult(Nil, CommandReply.Reply(reply))

  def withCmdEventsReply[E <: EntityEvent[_], C <: EntityCommand[_, _, _]](
      command: C
  )(events: List[E], reply: command.Reply): CommandProcessResult[E, command.Reply] =
    CommandProcessResult(events, CommandReply.Reply(reply))

  def withCmdEventReply[E <: EntityEvent[_], C <: EntityCommand[_, _, _]](
      command: C
  )(event: E, reply: command.Reply): CommandProcessResult[E, command.Reply] =
    CommandProcessResult(event :: Nil, CommandReply.Reply(reply))

  def withReply[E <: EntityEvent[_], R](reply: R): CommandProcessResult[E, R] =
    CommandProcessResult(Nil, CommandReply.Reply(reply))

  def withReply[E <: EntityEvent[_], R](events: List[E], reply: R): CommandProcessResult[E, R] =
    CommandProcessResult(events, CommandReply.Reply(reply))

  def withReply[E <: EntityEvent[_], R](event: E, reply: R): CommandProcessResult[E, R] =
    CommandProcessResult(event :: Nil, CommandReply.Reply(reply))

  def withNoReply[E <: EntityEvent[_]](): CommandProcessResult[E, Nothing] =
    CommandProcessResult(Nil, CommandReply.NoReply)
}

sealed trait CommandReply[+R]

object CommandReply {

  final case object NoReply extends CommandReply[Nothing]

  final case class Reply[R](reply: R) extends CommandReply[R]
}
