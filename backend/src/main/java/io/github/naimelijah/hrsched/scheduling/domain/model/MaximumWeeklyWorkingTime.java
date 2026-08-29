package io.github.naimelijah.hrsched.scheduling.domain.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Caps the total time worked across the whole schedule being evaluated.
 *
 * <p>ASSUMPTION, AND A KNOWN SIMPLIFICATION: this treats the roster period as the reference period.
 * Several jurisdictions average their weekly limit over a longer window, which this cannot express
 * — doing so needs history beyond the roster being checked. Recorded rather than hidden.
 *
 * @param maximum the longest permitted total, strictly positive
 */
public record MaximumWeeklyWorkingTime(Duration maximum) implements WorkingTimeConstraint {

  public MaximumWeeklyWorkingTime {
    Objects.requireNonNull(maximum, "maximum must not be null");
    if (maximum.isNegative() || maximum.isZero()) {
      throw new InvalidConstraintException("Maximum weekly working time must be positive");
    }
  }

  @Override
  public String name() {
    return "maximum weekly working time";
  }

  @Override
  public Optional<String> violatedBy(EmployeeSchedule schedule) {
    Duration total = schedule.totalWorkingTime();
    return total.compareTo(maximum) > 0
        ? Optional.of("%s worked exceeds the maximum of %s".formatted(total, maximum))
        : Optional.empty();
  }
}
