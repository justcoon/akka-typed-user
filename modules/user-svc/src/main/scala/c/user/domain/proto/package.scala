package c.user.domain

import c.user.domain.UserEntity.UserId
import c.user.domain.UserEntity._
import scalapb.TypeMapper
import com.google.protobuf.timestamp.Timestamp
import java.time.Instant

package object proto {

  implicit val userIdTypeMapper: TypeMapper[String, UserId] = TypeMapper[String, UserId](_.asUserId)(identity)

  implicit val instantTypeMapper: TypeMapper[Timestamp, Instant] = TypeMapper[Timestamp, Instant] { timestamp =>
    Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos)
  } { instant =>
    Timestamp.of(instant.getEpochSecond, instant.getNano)
  }
}
