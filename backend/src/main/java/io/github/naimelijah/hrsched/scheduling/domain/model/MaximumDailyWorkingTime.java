package io.github.naimelijah.hrsched.scheduling.domain.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Caps the time worked within a single calendar day.
 *
 * <p>The cap is configuration, not law written into code.
 *
 * <p>Counts against a day every hour actually worked on it, including hours from a shift that began
 * the previous day — see {@link EmployeeSchedule#workingTimePerDay()}. Attributing a whole shift to
 * the day it starts on would let an overnight shift plus a full next-day shift pass a cap that the
 * employee plainly exceeds.
 *
 * @param maximum the longest permitted working time in one day, strictly positive
 */
public record MaximumDailyWorkingTime(Duration maximum) implements WorkingTimeConstraint {

  public MaximumDailyWorkingTime {
    Objects.requireNonNull(maximum, "maximum must not be null");
    if (maximum.isNegative() || maximum.isZero()) {
      throw new InvalidConstraintException("Maximum daily working time must be positive");
    }
  }

  @Override
  public String name() {
    return "maximum daily working time";
  }

  @Override
  public Optional<String> violatedBy(EmployeeSchedule schedule) {
    return schedule.workingTimePerDay().entrySet().stream()
        .filter(entry -> entry.getValue().compareTo(maximum) > 0)
        .findFirst()
        .map(
            entry ->
                "%s worked on %s exceeds the maximum of %s"
                    .formatted(entry.getValue(), entry.getKey(), maximum));
  }
}
