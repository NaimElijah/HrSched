package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;

/**
 * Raised when one person would work two shifts at once (R3), whether both are on one roster or one
 * is already held on another authoritative roster (R35).
 *
 * <p>It holds whatever the organisation's constraint set says. Minimum rest is the only
 * working-time rule that notices an overlap, and it is optional, so an organisation that configured
 * no rules would otherwise publish somebody in two places — and no statute is needed to say that
 * cannot happen.
 *
 * <p>A 422 rather than a 400: the caller named real things and asked for something the domain
 * forbids.
 */
public class OverlappingAssignmentException extends RuntimeException {

  /**
   * Builds the refusal for two shifts on one roster. The message names the employee and both shifts
   * by identifier, never by name, and is the 422 response's detail.
   *
   * @param employee who would work both
   * @param shift the shift being assigned
   * @param overlapping the shift they already work that overlaps it
   */
  public OverlappingAssignmentException(EmployeeId employee, ShiftId shift, ShiftId overlapping) {
    super(
        "Employee %s would work shifts %s and %s, which overlap"
            .formatted(employee.value(), shift.value(), overlapping.value()));
  }

  /**
   * @param heldOn the authoritative roster that already carries {@code overlapping}, named so the
   *     Scheduler can find the clash outside the draft in front of them
   */
  public OverlappingAssignmentException(
      EmployeeId employee, ShiftId shift, ShiftId overlapping, RosterId heldOn) {
    super(
        ("Employee %s would work shift %s, which overlaps shift %s"
                + " they already work on published roster %s")
            .formatted(employee.value(), shift.value(), overlapping.value(), heldOn.value()));
  }
}
