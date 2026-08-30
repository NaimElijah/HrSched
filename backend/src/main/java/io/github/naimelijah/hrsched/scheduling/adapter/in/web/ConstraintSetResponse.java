package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumConsecutiveWorkingDays;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumWeeklyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.MinimumRestBetweenAssignments;
import io.github.naimelijah.hrsched.scheduling.domain.model.WorkingTimeConstraint;
import java.util.List;

/**
 * An organisation's rules, in the same shape they are submitted in.
 *
 * <p>The thresholds are returned, not only the rule names. Revising the rules replaces the whole
 * set — {@link ConstraintSet#revisedTo} does not merge — so a caller changing one rule must resend
 * the others, and it can only do that if it can read them back. Returning names alone made silently
 * dropping a rule the easiest thing for a client to do.
 *
 * <p>A null threshold means that rule is not enforced. There is no default for any of them anywhere
 * in this codebase, which is the point.
 *
 * @param version which revision of the rules this is
 * @param constraints the rules by name, for display
 * @param minimumRestMinutes shortest permitted gap between two assignments
 * @param maximumDailyMinutes longest permitted working time in one day
 * @param maximumWeeklyMinutes longest permitted working time across the roster period
 * @param maximumConsecutiveDays longest permitted run of consecutive working days
 */
public record ConstraintSetResponse(
    int version,
    List<String> constraints,
    Long minimumRestMinutes,
    Long maximumDailyMinutes,
    Long maximumWeeklyMinutes,
    Integer maximumConsecutiveDays) {

  static ConstraintSetResponse from(ConstraintSet constraintSet) {
    Long rest = null;
    Long daily = null;
    Long weekly = null;
    Integer consecutive = null;
    for (WorkingTimeConstraint constraint : constraintSet.constraints()) {
      // Exhaustive over the sealed hierarchy: a new constraint type will not compile until it is
      // given a wire representation here, which is what stops one silently becoming unreadable.
      switch (constraint) {
        case MinimumRestBetweenAssignments rule -> rest = rule.minimumRest().toMinutes();
        case MaximumDailyWorkingTime rule -> daily = rule.maximum().toMinutes();
        case MaximumWeeklyWorkingTime rule -> weekly = rule.maximum().toMinutes();
        case MaximumConsecutiveWorkingDays rule -> consecutive = rule.maximum();
      }
    }
    return new ConstraintSetResponse(
        constraintSet.version(),
        constraintSet.constraints().stream().map(WorkingTimeConstraint::name).toList(),
        rest,
        daily,
        weekly,
        consecutive);
  }
}
