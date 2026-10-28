package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumConsecutiveWorkingDays;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumWeeklyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.MinimumRestBetweenAssignments;
import io.github.naimelijah.hrsched.scheduling.domain.model.WorkingTimeConstraint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The working-time rules an organisation wants to apply, as whole minutes and counts.
 *
 * <p>Every field is optional: a null means that rule is simply not enforced. There is no default
 * value for any of them anywhere in this codebase, which is the point. Whoever configures an
 * organisation supplies the numbers, and a jurisdiction preset is a set of numbers someone loads,
 * not a constant a developer chose.
 *
 * @param minimumRestMinutes shortest permitted gap between two assignments
 * @param maximumDailyMinutes longest permitted working time in one day
 * @param maximumWeeklyMinutes longest permitted working time across the roster period
 * @param maximumConsecutiveDays longest permitted run of consecutive working days
 */
public record ConstraintSetRequest(
    Long minimumRestMinutes,
    Long maximumDailyMinutes,
    Long maximumWeeklyMinutes,
    Integer maximumConsecutiveDays) {

  /**
   * The most whole minutes, either way, whose nanoseconds a {@code long} still counts. Technical,
   * not a working-time ceiling: generation computes in nanoseconds ({@code Ticks}), so a duration
   * it cannot count in them could be stored but never used, failing every generation after it.
   */
  private static final long MOST_MINUTES_COUNTABLE_IN_NANOSECONDS =
      Duration.ofNanos(Long.MAX_VALUE).toMinutes();

  /**
   * Whether each value is a sensible rule is the domain's to decide; only one too large to compute
   * with is refused here.
   *
   * @return the domain constraints these numbers describe, omitting every rule left unset
   * @throws MalformedRequestException if a minute value's nanoseconds do not fit in a {@code long}
   */
  public List<WorkingTimeConstraint> toConstraints() {
    List<WorkingTimeConstraint> constraints = new ArrayList<>();
    if (minimumRestMinutes != null) {
      constraints.add(
          new MinimumRestBetweenAssignments(countable("minimumRestMinutes", minimumRestMinutes)));
    }
    if (maximumDailyMinutes != null) {
      constraints.add(
          new MaximumDailyWorkingTime(countable("maximumDailyMinutes", maximumDailyMinutes)));
    }
    if (maximumWeeklyMinutes != null) {
      constraints.add(
          new MaximumWeeklyWorkingTime(countable("maximumWeeklyMinutes", maximumWeeklyMinutes)));
    }
    if (maximumConsecutiveDays != null) {
      constraints.add(new MaximumConsecutiveWorkingDays(maximumConsecutiveDays));
    }
    return List.copyOf(constraints);
  }

  /** The detail names the field and never repeats the value sent (ADR-024). */
  private static Duration countable(String field, long minutes) {
    if (minutes > MOST_MINUTES_COUNTABLE_IN_NANOSECONDS
        || minutes < -MOST_MINUTES_COUNTABLE_IN_NANOSECONDS) {
      throw new MalformedRequestException(
          field + " is beyond what the system can compute with, in nanoseconds");
    }
    return Duration.ofMinutes(minutes);
  }
}
