package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Everything one employee is being asked to work, in order.
 *
 * <p>The input every {@link WorkingTimeConstraint} is evaluated against. Assembling it once and
 * handing it to each constraint keeps the constraints free of any knowledge about rosters,
 * assignments or shifts, so a new rule is a single small class.
 *
 * @param employee whose schedule this is
 * @param workingPeriods the spans they would work, each with the zone its days are counted in,
 *     sorted by start
 */
public record EmployeeSchedule(EmployeeId employee, List<WorkingPeriod> workingPeriods) {

  public EmployeeSchedule {
    Objects.requireNonNull(employee, "employee must not be null");
    workingPeriods =
        workingPeriods.stream()
            .sorted(Comparator.comparing(working -> working.period().start()))
            .toList();
  }

  /**
   * @return the total time worked across every period
   */
  public Duration totalWorkingTime() {
    return workingPeriods.stream()
        .map(working -> working.period().length())
        .reduce(Duration.ZERO, Duration::plus);
  }

  /**
   * Splits every period at midnight and totals the time falling on each calendar day.
   *
   * <p>Splitting rather than attributing a whole period to the day it starts on is what makes a
   * daily cap mean anything: a shift running 22:00 to 06:00 places two hours on the first day and
   * six on the second, so the second day's total includes work that began the day before.
   *
   * <p>Midnight is each period's own zone's (R36), so a day is as long as its clocks make it: 23
   * hours when they go forward, 25 when they go back. Work in two zones lands on each zone's own
   * date, and work on the same date adds up whichever zone it was in (ADR-034).
   *
   * @return time worked per calendar day, in date order, with no zero-length entries
   */
  public Map<LocalDate, Duration> workingTimePerDay() {
    Map<LocalDate, Duration> perDay = new TreeMap<>();
    for (WorkingPeriod working : workingPeriods) {
      working.timePerDay().forEach((day, time) -> perDay.merge(day, time, Duration::plus));
    }
    return Collections.unmodifiableMap(perDay);
  }

  /**
   * The distinct calendar days on which any time is actually worked.
   *
   * <p>A period's end is exclusive, so a shift finishing exactly at midnight does not make the
   * following day a working day.
   *
   * @return those days, in order
   */
  public Set<LocalDate> workingDays() {
    return Collections.unmodifiableSet(workingTimePerDay().keySet());
  }

  /**
   * @return true if this employee is asked to work nothing at all
   */
  public boolean isEmpty() {
    return workingPeriods.isEmpty();
  }
}
