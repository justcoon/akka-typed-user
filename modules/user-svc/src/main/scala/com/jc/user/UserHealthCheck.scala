package com.jc.user

import scala.concurrent.Future

import akka.actor.ActorSystem
import org.slf4j.LoggerFactory

// Enabled in application.conf
class UserHealthCheck(system: ActorSystem) extends (() => Future[Boolean]) {
  private val log = LoggerFactory.getLogger(getClass)

  override def apply(): Future[Boolean] = {
    log.debug("HealthCheck called")
    Future.successful(true)
  }
}
