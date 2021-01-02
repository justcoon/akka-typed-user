package com.jc.user.domain

import com.google.protobuf.timestamp.Timestamp
import com.jc.user.domain.DepartmentEntity.{ DepartmentId, _ }
import com.jc.user.domain.UserEntity.{ UserId, _ }
import scalapb.TypeMapper

import java.time.Instant

package object proto {

  implicit val userIdTypeMapper: TypeMapper[String, UserId]             = TypeMapper[String, UserId](_.asUserId)(identity)
  implicit val departmentIdTypeMapper: TypeMapper[String, DepartmentId] = TypeMapper[String, DepartmentId](_.asDepartmentId)(identity)

  implicit val instantTypeMapper: TypeMapper[Timestamp, Instant] = TypeMapper[Timestamp, Instant] { timestamp =>
    Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos)
  }(instant => Timestamp.of(instant.getEpochSecond, instant.getNano))
}
