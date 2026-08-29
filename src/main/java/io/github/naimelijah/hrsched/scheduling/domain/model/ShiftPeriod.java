package io.github.naimelijah.hrsched.scheduling.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The span of time a shift covers.
 *
 * <p>A value object that makes the illegal state unrepresentable: an instance cannot exist unless
 * it ends strictly after it starts. Callers therefore never have to check.
 *
 * <p>ASSUMPTION: instants, not local date-times with a zone. Rosters are usually read in a site's
 * local time, so this is likely to change once Q3 (multi-tenancy) and Q5 (deployment) are answered
 * — see {@code docs/requirements/requirements.md}.
 *
 * @param start when the shift begins, never {@code null}
 * @param end when the shift ends, never {@code null} and strictly after {@code start}
 * @throws InvalidShiftPeriodException if {@code end} is not strictly after {@code start}
 */
public record ShiftPeriod(Instant start, Instant end) {

  public ShiftPeriod {
    Objects.requireNonNull(start, "start must not be null");
    Objects.requireNonNull(end, "end must not be null");
    if (!end.isAfter(start)) {
      throw new InvalidShiftPeriodException(start, end);
    }
  }

  /**
   * @return how long the shift lasts, always positive
   */
  public Duration length() {
    return Duration.between(start, end);
  }
}
