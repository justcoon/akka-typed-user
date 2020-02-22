package com.jc.cqrs

trait EntityCommand[ID, S, R] {
  def entityId: ID
}
