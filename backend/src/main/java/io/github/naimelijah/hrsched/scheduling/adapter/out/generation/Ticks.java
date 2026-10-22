package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Time as exact whole numbers, for a solver that only adds integers.
 *
 * <p>One tick is the greatest common divisor, in nanoseconds, of every span the model mentions, so
 * each of them is a whole number of ticks and nothing is rounded. Rounding is not harmless here: a
 * rest gap of exactly the minimum passes, and one a nanosecond short does not.
 */
final class Ticks {

  private final Instant origin;
  private final long nanosPerTick;

  private Ticks(Instant origin, long nanosPerTick) {
    this.origin = origin;
    this.nanosPerTick = nanosPerTick;
  }

  /**
   * @param origin the instant counted as tick zero
   * @param spans every duration, and every instant's distance from {@code origin}, that will be
   *     expressed in ticks; any other will be refused
   * @return the coarsest tick in which all of them are whole
   */
  static Ticks dividing(Instant origin, Iterable<Duration> spans) {
    Objects.requireNonNull(origin, "origin must not be null");
    BigInteger divisor = BigInteger.ZERO;
    for (Duration span : spans) {
      divisor = divisor.gcd(BigInteger.valueOf(span.toNanos()).abs());
    }
    return new Ticks(origin, divisor.signum() == 0 ? 1 : divisor.longValueExact());
  }

  /**
   * @return how many ticks after the origin {@code instant} is
   * @throws IllegalStateException if it is not a whole number of ticks after it, which means it was
   *     not among the spans this was built from
   */
  long at(Instant instant) {
    return of(Duration.between(origin, instant));
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
