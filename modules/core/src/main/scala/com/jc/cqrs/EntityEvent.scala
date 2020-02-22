package com.jc.cqrs

trait EntityEvent[ID] {
  def entityId: ID
  def timestamp: java.time.Instant
}
