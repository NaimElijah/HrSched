package io.github.naimelijah.hrsched.workforce.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;

/**
 * Raised when no employee with the given identity exists in the organisation being acted on.
 *
 * <p>Does not distinguish "no such employee anywhere" from "exists, but in another organisation" —
 * telling the two apart would confirm the existence of another tenant's data.
 */
public class UnknownEmployeeException extends RuntimeException {

  /**
   * Builds the refusal for an identity this organisation does not hold.
   *
   * <p>The message names that identity, so it must not reach a response, which is never told back
   * what it sent (ADR-024). The web adapter answers with a constant sentence instead.
   *
   * @param id the identity that was asked for
   */
  public UnknownEmployeeException(EmployeeId id) {
    super("No such employee: %s".formatted(id.value()));
  }
}
