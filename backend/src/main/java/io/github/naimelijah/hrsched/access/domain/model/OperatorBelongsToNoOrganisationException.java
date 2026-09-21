package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.UserId;

/**
 * Raised when something is asked of an operator account that only makes sense inside an
 * organisation: giving it a role, or linking it to an employee.
 *
 * <p>A refusal rather than a no-op on purpose. An operator has no organisation, so there is no role
 * for it to hold and nobody for it to be; quietly ignoring the request would let a caller believe a
 * change had been stored, and an administration screen show a role the account does not have.
 *
 * <p>Keeps who was being changed as a field, so the refusal can be counted and searched for rather
 * than only read.
 */
public class OperatorBelongsToNoOrganisationException extends RuntimeException {

  private final UserId account;

  /**
   * @param account the operator account that was being changed
   */
  public OperatorBelongsToNoOrganisationException(UserId account) {
    super(
        "Account %s is the platform operator and belongs to no organisation"
            .formatted(account.value()));
    this.account = account;
  }

  /**
   * @return the operator account that was being changed
   */
  public UserId account() {
    return account;
  }
}
