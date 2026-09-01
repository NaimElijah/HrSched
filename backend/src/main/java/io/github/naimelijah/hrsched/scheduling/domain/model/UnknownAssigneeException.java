package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;

/**
 * Raised when someone is assigned who is not among the organisation's rosterable people.
 *
 * <p>This is rule R5 - an employee may only be assigned within their own organisation - enforced on
 * the manual path as well as the generated one. Scheduling never holds a workforce entity, so
 * "exists" here means "came back through the published port", which is the only sense of the word
 * this context is entitled to.
 */
public class UnknownAssigneeException extends RuntimeException {

  public UnknownAssigneeException(EmployeeId employee) {
    super("No such employee %s in this organisation".formatted(employee.value()));
  }
}
