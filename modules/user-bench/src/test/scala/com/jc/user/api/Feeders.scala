package com.jc.user.api

import io.gatling.core.Predef._

object Feeders {

  val departmentIdFeeder = Array("d1", "d2", "d3", "d4").map(v => Map("id" -> v)).random

  val countryFeeder = Array("CA", "UK", "UA", "US", "MX").map(v => Map("id" -> v)).random

  val suggestFeeder = Array("bla", "blue", "red", "bl").map(v => Map("id" -> v)).random
}