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

import akka.actor.ActorSystem
import akka.persistence.inmemory.query.scaladsl.InMemoryReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.ActorMaterializer
import akka.testkit.TestProbe
import org.scalatest.{ AsyncWordSpec, BeforeAndAfterAll, Matchers }

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class UserRepositorySpec extends AsyncWordSpec with Matchers with BeforeAndAfterAll {
//  import UserRepository._
//
//  private implicit val system = ActorSystem()
//
//  private implicit val mat = ActorMaterializer()
//
//  private val readJournal = PersistenceQuery(system)
//    .readJournalFor[InMemoryReadJournal](InMemoryReadJournal.Identifier)
//
//  private val user = User(0, "jsnow", "Jon Snow", "jsnow@gabbler.io")
//
//  "UserRepository" should {
//    "correctly handle getting, adding and removing users" in {
//      import user._
//
//      val userRepository     = system.actorOf(UserRepository(readJournal))
//      val sender             = TestProbe()
//      implicit val senderRef = sender.ref
//
//      userRepository ! GetUsers
//      sender.expectMsg(Users(Set.empty))
//
//      userRepository ! AddUser(username, nickname, email)
//      val userAdded = sender.expectMsg(UserAdded(user))
//      userRepository ! GetUsers
//      sender.expectMsg(Users(Set(user)))
//
//      userRepository ! AddUser(username, "Jon Targaryen", "jtargaryen@gabbler.io")
//      sender.expectMsg(UsernameTaken(username))
//
//      userRepository ! RemoveUser(id)
//      val userRemoved = sender.expectMsg(UserRemoved(user))
//      userRepository ! GetUsers
//      sender.expectMsg(Users(Set.empty))
//
//      userRepository ! RemoveUser(id)
//      sender.expectMsg(IdUnknown(id))
//
//      userRepository ! GetUserEvents(0)
//      val userEvents = sender.expectMsgPF(hint = "source of user events") {
//        case UserEvents(e) => e
//      }
//      userEvents
//        .take(2)
//        .runFold(Vector.empty[(Long, UserEvent)])(_ :+ _)
//        .map(
//          _ should contain inOrder (
//            (1, userAdded), // The first event has seqNo 1!
//            (2, userRemoved)
//          )
//        )
//    }
//  }
//
//  override protected def afterAll() = {
//    Await.ready(system.terminate(), 42.seconds)
//    super.afterAll()
//  }
}
