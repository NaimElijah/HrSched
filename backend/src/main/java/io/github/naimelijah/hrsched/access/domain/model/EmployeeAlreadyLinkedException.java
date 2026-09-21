package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;

/**
 * Raised when an employee is linked to a second account within the same organisation.
 *
 * <p>One person, one account: two accounts claiming the same employee would both answer {@code
 * isThemself} for that person, so self-service would be reachable by whoever holds either
 * credential (ADR-012).
 *
 * <p>Carries the employee identity, which is an identifier rather than personal data, so a refusal
 * can be counted and traced without naming anybody.
 */
public class EmployeeAlreadyLinkedException extends RuntimeException {

  private final EmployeeId employee;

  /**
   * @param employee the personnel record already linked to another account
   * @param cause the integrity violation the database reported
   */
  public EmployeeAlreadyLinkedException(EmployeeId employee, Throwable cause) {
    super("Employee %s is already linked to an account".formatted(employee.value()), cause);
    this.employee = employee;
  }

  /**
   * @return the personnel record already linked to another account
   */
  public EmployeeId employee() {
    return employee;
  }
}
