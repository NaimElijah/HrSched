package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.Objects;

/**
 * Work somebody already holds on another roster that stays authoritative beside the one being
 * checked: an assignment with the time its shift runs attached.
 *
 * <p>It exists so R3 can be judged across rosters (R35) without the roster being checked having to
 * hold anybody else's assignments.
 *
 * @param roster the authoritative roster carrying the work
 * @param shift the shift worked
 * @param employee who works it
 * @param period when the shift runs
 */
public record HeldWork(RosterId roster, ShiftId shift, EmployeeId employee, TimeRange period) {

  public HeldWork {
    Objects.requireNonNull(roster, "roster must not be null");
    Objects.requireNonNull(shift, "shift must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
    Objects.requireNonNull(period, "period must not be null");
  }
}
