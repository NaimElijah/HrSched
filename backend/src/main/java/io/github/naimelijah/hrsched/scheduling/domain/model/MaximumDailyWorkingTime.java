package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Caps the time worked within a single calendar day.
 *
 * <p>The cap is configuration, not law written into code.
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
    Map<LocalDate, Duration> perDay = new HashMap<>();
    for (TimeRange period : schedule.workingPeriods()) {
      LocalDate day = LocalDate.ofInstant(period.start(), ZoneOffset.UTC);
      perDay.merge(day, period.length(), Duration::plus);
    }
    return perDay.entrySet().stream()
        .filter(entry -> entry.getValue().compareTo(maximum) > 0)
        .findFirst()
        .map(
            entry ->
                "%s worked on %s exceeds the maximum of %s"
                    .formatted(entry.getValue(), entry.getKey(), maximum));
  }
}
