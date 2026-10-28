package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.InvalidTimeRangeException;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Turns the two instants a request sent into a span, refusing one the store could not hold or
 * generation could not count.
 *
 * <p>The limits are technical, not rules of the business, and they are checked here so a caller's
 * out-of-range value is answered 400 instead of reaching the adapter and failing there as a 500.
 * Sub-microsecond precision is not refused as such: ADR-040 accepts a span declared beyond
 * microseconds and stores it at the microsecond. Only a span that no longer ends after it starts
 * once stored is refused, because the table would refuse it.
 *
 * <p>One limit is the solver's, not the store's: generation counts each shift's length and position
 * in nanoseconds, as a {@code long} ({@code RosterModel.ticksFor}, {@code Ticks}), so a span longer
 * than that counts could be stored and then fail every generation overlapping it. It is the same
 * kind of bound as the one {@code ConstraintSetRequest} holds working-time minutes to, and says
 * nothing about how long a shift may sensibly be; a business ceiling on that is the owner's to set
 * (#262).
 *
 * <p>Workforce's web adapter holds the same storage check. It is copied rather than shared because
 * a context may reach another only through its published ports (ADR-008), and this is adapter
 * plumbing, not a port.
 *
 * <p>No message repeats a value sent (ADR-024).
 */
final class StorableSpans {

  /**
   * The first instant stored as sent. PostgreSQL's {@code timestamptz} begins at 4714-11-24 BC, but
   * pgjdbc writes anything before 4713-01-01 BC as {@code -infinity}, so the later of the two is
   * where storing stops being faithful.
   */
  private static final Instant EARLIEST_STORABLE = Instant.parse("-4712-01-01T00:00:00Z");

  /** The last instant PostgreSQL's {@code timestamptz} holds, at its microsecond precision. */
  private static final Instant LATEST_STORABLE = Instant.parse("+294276-12-31T23:59:59.999999Z");

  /**
   * pgjdbc rounds an instant half-up to the microsecond before sending it, so this is added before
   * truncating to reproduce what is stored.
   */
  private static final long HALF_A_MICROSECOND_IN_NANOS = 500;

  /** The longest span whose nanoseconds a {@code long} counts. Technical, not a rule. */
  private static final Duration MOST_COUNTABLE_IN_NANOSECONDS = Duration.ofNanos(Long.MAX_VALUE);

  private StorableSpans() {}

  /**
   * @param start when the span begins, never null
   * @param end when the span ends, never null
   * @return the span the two instants describe
   * @throws InvalidTimeRangeException if the span ends at or before it starts, as sent
   * @throws MalformedRequestException if either instant lies outside what the store holds, the span
   *     would no longer end after it starts once stored at microsecond precision, or it would last
   *     longer, once stored, than a {@code long} counts in nanoseconds
   */
  static TimeRange toPeriod(Instant start, Instant end) {
    TimeRange period = new TimeRange(start, end);
    if (!storable(start) || !storable(end)) {
      throw new MalformedRequestException(
          "start and end must each lie within the instants that can be stored");
    }
    if (!asStored(end).isAfter(asStored(start))) {
      throw new MalformedRequestException(
          "The span must still end after it starts at the microsecond, the precision it is stored"
              + " at");
    }
    if (Duration.between(asStored(start), asStored(end)).compareTo(MOST_COUNTABLE_IN_NANOSECONDS)
        > 0) {
      throw new MalformedRequestException(
          "The span must last no longer than 2^63 - 1 nanoseconds, the most generation can count");
    }
    return period;
  }

  private static boolean storable(Instant instant) {
    return !instant.isBefore(EARLIEST_STORABLE) && !instant.isAfter(LATEST_STORABLE);
  }

  /** Only called within the storable range, where adding half a microsecond cannot overflow. */
  private static Instant asStored(Instant instant) {
    return instant.plusNanos(HALF_A_MICROSECOND_IN_NANOS).truncatedTo(ChronoUnit.MICROS);
  }
}
