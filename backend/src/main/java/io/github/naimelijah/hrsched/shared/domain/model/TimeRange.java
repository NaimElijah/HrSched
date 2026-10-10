package io.github.naimelijah.hrsched.shared.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A span of time that ends strictly after it starts.
 *
 * <p>A value object in the shared kernel because more than one context needs it and none of them
 * owns it: a shift covers one, an employee is unavailable for one. It carries no domain meaning of
 * its own, which is what makes it safe to share.
 *
 * <p>Makes the illegal state unrepresentable — an instance cannot exist unless it ends after it
 * starts, so no caller has to check.
 *
 * <p>Instants, with no zone. Where a zone matters it is carried beside this, by whatever owns it: a
 * shift's working-time days are counted in the shift's own zone (R36, ADR-034), and the kernel
 * stays free of a choice only one context makes.
 *
 * @param start when the span begins, never {@code null}
 * @param end when the span ends, never {@code null} and strictly after {@code start}
 * @throws InvalidTimeRangeException if {@code end} is not strictly after {@code start}
 */
public record TimeRange(Instant start, Instant end) {

  public TimeRange {
    Objects.requireNonNull(start, "start must not be null");
    Objects.requireNonNull(end, "end must not be null");
    if (!end.isAfter(start)) {
      throw new InvalidTimeRangeException(start, end);
    }
  }

  /**
   * @return how long the span lasts, always positive
   */
  public Duration length() {
    return Duration.between(start, end);
  }

  /**
   * @param other another span
   * @return whether the two spans share any instant
   */
  public boolean overlaps(TimeRange other) {
    Objects.requireNonNull(other, "other must not be null");
    return start.isBefore(other.end) && other.start.isBefore(end);
  }

  /**
   * @param other a span assumed not to overlap this one
   * @return the gap between them, or {@link Duration#ZERO} if they touch or overlap
   */
  public Duration gapTo(TimeRange other) {
    Objects.requireNonNull(other, "other must not be null");
    if (overlaps(other)) {
      return Duration.ZERO;
    }
    return start.isBefore(other.start)
        ? Duration.between(end, other.start)
        : Duration.between(other.end, start);
  }
}
