package com.jc.user.domain

import com.jc.cqrs.EntityEvent
import com.jc.user.domain.DepartmentEntity.DepartmentId
import io.circe.{ Decoder, Encoder }
import shapeless.tag
import shapeless.tag.@@

object UserEntity {

  implicit val userIdDecoder: Decoder[UserId] = Decoder[String].map(_.asUserId)
  implicit val userIdEncoder: Encoder[UserId] = Encoder.encodeString.contramap(identity)

  implicit class UserIdTaggerOps(v: String) {
    val asUserId: UserId = tag[UserIdTag][String](v)

    def as[U]: String @@ U = tag[U][String](v)
  }

  trait UserIdTag

  type UserId = String @@ UserIdTag

  trait UserEvent extends EntityEvent[UserId]

  trait DepartmentEvent extends EntityEvent[DepartmentId]
}
