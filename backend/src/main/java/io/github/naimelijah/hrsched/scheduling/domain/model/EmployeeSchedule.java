package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything one employee is being asked to work, in order.
 *
 * <p>The input every {@link WorkingTimeConstraint} is evaluated against. Assembling it once and
 * handing it to each constraint keeps the constraints free of any knowledge about rosters,
 * assignments or shifts, so a new rule is a single small class.
 *
 * @param employee whose schedule this is
 * @param workingPeriods the spans they would work, sorted by start
 */
public record EmployeeSchedule(EmployeeId employee, List<TimeRange> workingPeriods) {

  public EmployeeSchedule {
    Objects.requireNonNull(employee, "employee must not be null");
    workingPeriods =
        workingPeriods.stream().sorted(Comparator.comparing(TimeRange::start)).toList();
  }

  /**
   * @return the total time worked across every period
   */
  public Duration totalWorkingTime() {
    return workingPeriods.stream().map(TimeRange::length).reduce(Duration.ZERO, Duration::plus);
  }

  /**
   * @return the distinct calendar days touched by any period, in order
   *     <p>ASSUMPTION: days are counted in UTC, because a {@link TimeRange} carries no zone yet.
   *     For an organisation whose shifts cross midnight in local time this will miscount, which is
   *     one more reason the zone question has to be answered before this is trusted in production.
   */
  public Set<LocalDate> workingDays() {
    Set<LocalDate> days = new TreeSet<>();
    for (TimeRange period : workingPeriods) {
      LocalDate day = LocalDate.ofInstant(period.start(), ZoneOffset.UTC);
      LocalDate last = LocalDate.ofInstant(period.end(), ZoneOffset.UTC);
      while (!day.isAfter(last)) {
        days.add(day);
        day = day.plusDays(1);
      }
    }
    return days;
  }

  /**
   * @return true if this employee is asked to work nothing at all
   */
  public boolean isEmpty() {
    return workingPeriods.isEmpty();
  }
}
