package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;

/**
 * Raised when an account is linked to a personnel record its organisation does not hold.
 *
 * <p>The link is what makes self-service possible: it is how an actor answers "is this me?" without
 * comparing a {@code UserId} with an {@code EmployeeId} (ADR-012). A link to somebody else's
 * employee would therefore hand one organisation's account the self-service rights of another's
 * staff, so it is checked against the workforce rather than trusted.
 *
 * <p>Distinct from {@link EmployeeAlreadyLinkedException}, which is about a record that does exist
 * here and is already spoken for.
 */
public class NotAnEmployeeHereException extends RuntimeException {

  private final EmployeeId employee;

  /**
   * @param employee the personnel record that was named
   */
  public NotAnEmployeeHereException(EmployeeId employee) {
    super("No employee %s in this organisation".formatted(employee.value()));
    this.employee = employee;
  }

  /**
   * @return the personnel record that was named
   */
  public EmployeeId employee() {
    return employee;
  }
}
