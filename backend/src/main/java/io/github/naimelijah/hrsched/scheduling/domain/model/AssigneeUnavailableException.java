package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;

/**
 * Raised when somebody is assigned to a shift running through time they said they cannot work.
 *
 * <p>Unavailability is the hard half of what an employee declares; a stated preference ({@link
 * StatedPreferences}) is the soft half, and never blocks an assignment. Requirement F6 is "declare
 * when they cannot work", and a system that accepts that declaration and then rosters somebody
 * anyway, silently, is the failure the declaration exists to prevent.
 *
 * <p>A 422 rather than a 404: the caller named real things and asked for something the domain
 * forbids.
 */
public class AssigneeUnavailableException extends RuntimeException {

  /**
   * Builds the refusal. The message names the employee and the shift by identifier, never by name,
   * and not what was declared or when; it is the 422 response's detail.
   *
   * @param employee who was to be assigned
   * @param shift the shift that runs through time they declared they cannot work
   */
  public AssigneeUnavailableException(EmployeeId employee, ShiftId shift) {
    super(
        "Employee %s has declared they cannot work when shift %s runs"
            .formatted(employee.value(), shift.value()));
  }
}
