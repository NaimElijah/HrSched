package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;

/** Raised when a shift cannot be filled by anyone eligible and available. */
public class ShiftCannotBeCoveredException extends RuntimeException {

  public ShiftCannotBeCoveredException(ShiftId shift, int required, int found) {
    super(
        "Shift %s needs %d people but only %d are eligible and available"
            .formatted(shift.value(), required, found));
  }

  public ShiftCannotBeCoveredException(ShiftId shift, EmployeeId employee) {
    super("Employee %s cannot cover shift %s".formatted(employee.value(), shift.value()));
  }
}
