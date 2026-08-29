package io.github.naimelijah.hrsched.scheduling.domain.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Caps how many days in a row an employee may be asked to work.
 *
 * @param maximum the longest permitted run of consecutive days, at least one
 */
public record MaximumConsecutiveWorkingDays(int maximum) implements WorkingTimeConstraint {

  public MaximumConsecutiveWorkingDays {
    if (maximum < 1) {
      throw new InvalidConstraintException("Maximum consecutive working days must be at least one");
    }
  }

  @Override
  public String name() {
    return "maximum consecutive working days";
  }

  @Override
  public Optional<String> violatedBy(EmployeeSchedule schedule) {
    List<LocalDate> days = List.copyOf(schedule.workingDays());
    int run = 0;
    LocalDate previous = null;
    for (LocalDate day : days) {
      run = previous != null && day.equals(previous.plusDays(1)) ? run + 1 : 1;
      if (run > maximum) {
        return Optional.of(
            "%d consecutive working days ending %s exceeds the maximum of %d"
                .formatted(run, day, maximum));
      }
      previous = day;
    }
    return Optional.empty();
  }
}
