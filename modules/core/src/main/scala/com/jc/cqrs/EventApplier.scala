package com.jc.cqrs

trait EventApplier[S, E <: EntityEvent[_]] {
  def apply(state: S, event: E): S
}

trait InitialEventApplier[S, E <: EntityEvent[_]] {
  def apply(event: E): Option[S]
}
