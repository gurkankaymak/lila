package lila.memo

import com.github.blemale.scaffeine.{ Cache, Scaffeine }

import ornicar.scalalib.Zero
import scala.concurrent.duration.Duration

/**
 * side effect throttler that allows X ops per Y unit of time
 */
final class RateLimit(
    credits: Int,
    duration: Duration,
    name: String,
    key: String) {

  private type Cost = Int
  private type ClearAt = Long

  private val storage = Scaffeine()
    .expireAfterWrite(duration)
    .build[String, (Cost, ClearAt)]()

  private def makeClearAt = nowMillis + duration.toMillis

  private val logger = lila.log("ratelimit")
  private val monitor = lila.mon.security.rateLimit.generic(key)

  logger.info(s"[start] $name ($credits/$duration)")

  def apply[A](k: String, cost: Cost = 1, msg: => String = "")(op: => A)(implicit default: Zero[A]): A =
    storage getIfPresent k match {
      case None =>
        storage.put(k, cost -> makeClearAt)
        op
      case Some((a, clearAt)) if a <= credits =>
        storage.put(k, (a + cost) -> clearAt)
        op
      case Some((_, clearAt)) if nowMillis > clearAt =>
        storage.put(k, cost -> makeClearAt)
        op
      case _ =>
        logger.info(s"$name ($credits/$duration) $k cost: $cost $msg")
        monitor()
        default.zero
    }
}
