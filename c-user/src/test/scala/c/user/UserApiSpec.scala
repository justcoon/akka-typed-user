/*
 * Copyright 2016 Heiko Seeberger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package c.user

import akka.NotUsed
import akka.actor.ActorRef
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.scaladsl.Source
import akka.testkit.{ TestActor, TestDuration, TestProbe }
import akka.util.Timeout
import org.scalatest.{ AsyncWordSpec, Matchers, Succeeded }

import scala.concurrent.duration.DurationInt

class UserApiSpec extends AsyncWordSpec with Matchers with ScalatestRouteTest {

  import io.circe.generic.auto._
  import io.circe.syntax._

  private implicit val timeout = Timeout(1.second.dilated)

//  private val user = User(0, "jsnow", "Jon Snow", "jsnow@gabbler.io")

//  "UserApi" should {
//    "terminate if it can't bind to a socket" in {
//      val probe   = TestProbe()
//      val userApi = system.actorOf(UserApi("localhost", 80, system.deadLetters, 1.second.dilated))
//      probe.watch(userApi)
//      probe.expectTerminated(userApi)
//      Succeeded
//    }
//  }
//
//  "UserApi's route" should {
//    "respond to GET /users with an OK" in {
//      val userRepository = TestProbe()
//      userRepository.setAutoPilot(
//        (sender: ActorRef, msg: Any) =>
//          msg match {
//            case GetUsers =>
//              sender ! Users(Set(user))
//              TestActor.NoAutoPilot
//        }
//      )
//      Get("/users") ~> UserApi.route(userRepository.ref) ~> check {
//        status shouldBe OK
//        responseAs[Set[User]] shouldBe Set(user)
//      }
//    }
//
//    "respond to an invalid POST /users with a Conflict" in {
//      import user._
//      val userRepository = TestProbe()
//      userRepository.setAutoPilot(
//        (sender: ActorRef, msg: Any) =>
//          msg match {
//            case AddUser(`username`, `nickname`, `email`) =>
//              sender ! UsernameTaken(username)
//              TestActor.NoAutoPilot
//        }
//      )
//      val request = Post("/users", AddUser(username, nickname, email))
//      request ~> UserApi.route(userRepository.ref) ~> check {
//        status shouldBe Conflict
//        responseAs[String] shouldBe s"Username $username taken!"
//      }
//    }
//
//    "respond to a valid POST /users with a Created" in {
//      import user._
//      val userRepository = TestProbe()
//      userRepository.setAutoPilot(
//        (sender: ActorRef, msg: Any) =>
//          msg match {
//            case AddUser(`username`, `nickname`, `email`) =>
//              sender ! UserAdded(user)
//              TestActor.NoAutoPilot
//        }
//      )
//      val request = Post("/users", AddUser(username, nickname, email))
//      request ~> UserApi.route(userRepository.ref) ~> check {
//        status shouldBe Created
//        header(Location.name) shouldBe defined
//        header(Location.name).get.value should endWith(s"/users/$id")
//        responseAs[User] shouldBe user
//      }
//    }
//
//    "respond to an invalid DELETE /users/<id> with a NotFound" in {
//      import user._
//      val userRepository = TestProbe()
//      userRepository.setAutoPilot(
//        (sender: ActorRef, msg: Any) =>
//          msg match {
//            case RemoveUser(`id`) =>
//              sender ! IdUnknown(id)
//              TestActor.NoAutoPilot
//        }
//      )
//      Delete(s"/users/$id") ~> UserApi.route(userRepository.ref) ~> check {
//        status shouldBe NotFound
//        responseAs[String] shouldBe s"User with id $id not found!"
//      }
//    }
//
//    "respond to a valid DELETE /users/<id> with a NoContent" in {
//      import user._
//      val userRepository = TestProbe()
//      userRepository.setAutoPilot(
//        (sender: ActorRef, msg: Any) =>
//          msg match {
//            case RemoveUser(`id`) =>
//              sender ! UserRemoved(user)
//              TestActor.NoAutoPilot
//        }
//      )
//      Delete(s"/users/$id") ~> UserApi.route(userRepository.ref) ~> check {
//        status shouldBe NoContent
//      }
//    }
//  }
//
//  "respond to a valid GET /user-events with an OK" in {
//    val userRepository = TestProbe(): TestProbe
//    userRepository.setAutoPilot(new TestActor.AutoPilot {
//      override def run(sender: ActorRef, msg: Any) = msg match {
//        case GetUserEvents(1) =>
//          sender ! UserEvents(
//            Source(Vector((1L, UserAdded(user)), (2L, UserRemoved(user))))
//          )
//          TestActor.NoAutoPilot
//      }
//    })
//    Get("/user-events") ~> UserApi.route(userRepository.ref) ~> check {
//      status shouldBe OK
//      val userEvents = responseAs[Source[ServerSentEvent, NotUsed]]
//      userEvents
//        .take(2)
//        .runFold(Vector.empty[ServerSentEvent])(_ :+ _)
//        .map(
//          _ shouldBe Vector(
//            ServerSentEvent(user.asJson.noSpaces, "user-added", "1"),
//            ServerSentEvent(user.asJson.noSpaces, "user-removed", "2")
//          )
//        )
//    }
//  }
//
//  "respond to a valid GET /user-events with a Last-Event-ID with an OK" in {
//    val userRepository = TestProbe(): TestProbe
//    userRepository.setAutoPilot(new TestActor.AutoPilot {
//      override def run(sender: ActorRef, msg: Any) = msg match {
//        case GetUserEvents(2) =>
//          sender ! UserEvents(Source(Vector((2L, UserRemoved(user)))))
//          TestActor.NoAutoPilot
//      }
//    })
//    val request = Get("/user-events").withHeaders(`Last-Event-ID`("1"))
//    request ~> UserApi.route(userRepository.ref) ~> check {
//      status shouldBe OK
//      val eventSource = responseAs[Source[ServerSentEvent, NotUsed]]
//      eventSource
//        .take(2)
//        .runFold(Vector.empty[ServerSentEvent])(_ :+ _)
//        .map(
//          _ shouldBe Vector(
//            ServerSentEvent(user.asJson.noSpaces, "user-removed", "2")
//          )
//        )
//    }
//  }
//
//  "respond to a GET /user-events with an invalid Last-Event-ID with an BadRequest" in {
//    val request = Get("/user-events").withHeaders(`Last-Event-ID`("x"))
//    request ~> UserApi.route(system.deadLetters) ~> check {
//      status shouldBe BadRequest
//    }
//  }
}
