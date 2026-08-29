package io.github.naimelijah.hrsched.workforce.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;

/**
 * Raised when no employee with the given identity exists in the organisation being acted on.
 *
 * <p>Does not distinguish "no such employee anywhere" from "exists, but in another organisation" —
 * telling the two apart would confirm the existence of another tenant's data.
 */
public class UnknownEmployeeException extends RuntimeException {

  public UnknownEmployeeException(EmployeeId id) {
    super("No such employee: %s".formatted(id.value()));
  }
}
