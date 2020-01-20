package c.cqrs.offsetstore

import scalapb.TypeMapper
import com.google.protobuf.timestamp.Timestamp
import java.time.Instant
import java.util.UUID

import akka.persistence.query.{ NoOffset, Offset, Sequence, TimeBasedUUID }
import c.cqrs.offsetstore.OffsetStoreEntity._

package object proto {

  implicit val offsetStoreIdIdTypeMapper: TypeMapper[String, OffsetStoreId] = TypeMapper[String, OffsetStoreId](_.asOffsetStoreId)(identity)

  implicit val instantTypeMapper: TypeMapper[Timestamp, Instant] = TypeMapper[Timestamp, Instant] { timestamp =>
    Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos)
  } { instant =>
    Timestamp.of(instant.getEpochSecond, instant.getNano)
  }

  implicit val offsetTypeMapper: TypeMapper[OffsetProto, Offset] = TypeMapper[OffsetProto, Offset] { proto =>
    proto.value match {
      case OffsetProto.Value.TimeBasedUUID(v) => TimeBasedUUID(UUID.fromString(v))
      case OffsetProto.Value.Sequence(v)      => Sequence(v)
      case OffsetProto.Value.Empty            => NoOffset
    }
  } { offset =>
    val value = offset match {
      case TimeBasedUUID(v) => OffsetProto.Value.TimeBasedUUID(v.toString)
      case Sequence(v)      => OffsetProto.Value.Sequence(v)
      case NoOffset         => OffsetProto.Value.Empty
    }
    OffsetProto(value)
  }
}
