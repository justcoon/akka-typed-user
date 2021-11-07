package com.jc.user.domain

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import com.jc.user.CassandraUtils
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpecLike
import com.jc.user.domain.UserEntity._

import java.util.UUID
import scala.concurrent.duration._
import scala.io.Source

class UserServiceSpec extends AsyncWordSpecLike with Matchers with BeforeAndAfterAll {
  private val cfg     = ConfigFactory.load("application-test.conf")
  private val testKit = ActorTestKit("user", cfg)

  implicit private val sys                       = testKit.system
  implicit private val classicSys                = testKit.system.toClassic
  implicit private val ec                        = testKit.system.dispatchers
  implicit private val sharding: ClusterSharding = ClusterSharding(sys)
  implicit private val askTimeout: Timeout       = 30.seconds

  private val addressValidationService = SimpleAddressValidationService
  private val departmentService        = DepartmentService()
  private val userService              = UserService(departmentService, addressValidationService)

  "UserService" should {
    "createUser" in {
      val id = UUID.randomUUID().toString.asUserId
      userService.sendCommand(UserAggregate.CreateUserCommand(id, "c", "c@cc.com", "p")).map { res =>
        res shouldBe UserAggregate.UserCreatedReply(id)
      }
    }
  }

  override def beforeAll(): Unit = {
    val statements =
      CassandraUtils.readCqlStatements(Source.fromInputStream(getClass.getResourceAsStream("/cassandra/migrations/1_init.cql")))
    CassandraUtils.init(statements)
  }

  override def afterAll(): Unit =
    testKit.shutdownTestKit()
}
