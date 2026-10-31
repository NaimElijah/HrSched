package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import java.util.UUID;

/**
 * The body of a request to put one person on one shift of a draft.
 *
 * @param shift which shift is being covered
 * @param employee who covers it
 */
public record AssignmentRequest(UUID shift, UUID employee) {

  /**
   * @return the shift named
   * @throws MalformedRequestException if the field is absent, which Jackson maps to null rather
   *     than rejecting
   */
  ShiftId requiredShift() {
    if (shift == null) {
      throw new MalformedRequestException("shift is required");
    }
    return new ShiftId(shift);
  }

  /**
   * @return the employee named
   * @throws MalformedRequestException if the field is absent
   */
  EmployeeId requiredEmployee() {
    if (employee == null) {
      throw new MalformedRequestException("employee is required");
    }
    return new EmployeeId(employee);
  }
}
