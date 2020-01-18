package c

import java.time.{ Duration => JavaDuration }

import scala.concurrent.duration.{ FiniteDuration, NANOSECONDS }

package object user {

  type Traversable[+A] = scala.collection.immutable.Traversable[A]
  type Iterable[+A]    = scala.collection.immutable.Iterable[A]
  type Seq[+A]         = scala.collection.immutable.Seq[A]
  type IndexedSeq[+A]  = scala.collection.immutable.IndexedSeq[A]

  final implicit class JavaDurationOps(val duration: JavaDuration) extends AnyVal {
    def asScala: FiniteDuration = FiniteDuration(duration.toNanos, NANOSECONDS)
  }

}
