package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import java.math.BigInteger;
import java.time.Duration;

/**
 * Time as exact whole numbers, for a solver that only adds integers.
 *
 * <p>One tick is the greatest common divisor, in nanoseconds, of every span the model mentions, so
 * each of them is a whole number of ticks and nothing is rounded. Rounding is not harmless here: a
 * day whose shares of work add up to exactly the daily cap passes, and one a nanosecond over does
 * not.
 */
final class Ticks {

  private final long nanosPerTick;

  private Ticks(long nanosPerTick) {
    this.nanosPerTick = nanosPerTick;
  }

  /**
   * @param spans every duration that will be expressed in ticks; any other will be refused
   * @return the coarsest tick in which all of them are whole
   */
  static Ticks dividing(Iterable<Duration> spans) {
    BigInteger divisor = BigInteger.ZERO;
    for (Duration span : spans) {
      divisor = divisor.gcd(BigInteger.valueOf(span.toNanos()).abs());
    }
    return new Ticks(divisor.signum() == 0 ? 1 : divisor.longValueExact());
  }

  /**
   * @return {@code duration} in ticks
   * @throws IllegalStateException if it is not a whole number of ticks
   */
  long of(Duration duration) {
    long nanos = duration.toNanos();
    if (nanos % nanosPerTick != 0) {
      throw new IllegalStateException(
          "%s is not a whole number of %d ns ticks".formatted(duration, nanosPerTick));
    }
    return nanos / nanosPerTick;
  }
}
