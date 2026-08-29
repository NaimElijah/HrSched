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
   * @return the domain constraints these numbers describe, omitting every rule left unset
   */
  public List<WorkingTimeConstraint> toConstraints() {
    List<WorkingTimeConstraint> constraints = new ArrayList<>();
    if (minimumRestMinutes != null) {
      constraints.add(new MinimumRestBetweenAssignments(Duration.ofMinutes(minimumRestMinutes)));
    }
    if (maximumDailyMinutes != null) {
      constraints.add(new MaximumDailyWorkingTime(Duration.ofMinutes(maximumDailyMinutes)));
    }
    if (maximumWeeklyMinutes != null) {
      constraints.add(new MaximumWeeklyWorkingTime(Duration.ofMinutes(maximumWeeklyMinutes)));
    }
    if (maximumConsecutiveDays != null) {
      constraints.add(new MaximumConsecutiveWorkingDays(maximumConsecutiveDays));
    }
    return List.copyOf(constraints);
  }
}
