package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.UserId;

/**
 * Raised when an account holding the Employee role would be linked to no employee, or would be
 * usable while it is not (R34).
 *
 * <p>The link is what an Employee account is for. It is how self-service answers "is this me?"
 * (ADR-012), so an Employee linked to nobody signs in to find nothing of theirs. Such an account is
 * therefore never created, never made by a change, and never restored. One stored before the rule
 * can still exist, disabled by migration {@code V9}, and stays disabled until it is linked.
 *
 * <p>Carries the account the change was aimed at, so a refusal can be traced without naming
 * anybody. On creation that is the identity the refused account would have had, which nothing
 * stores.
 */
public class UnlinkedEmployeeAccountException extends RuntimeException {

  private final UserId account;

  /**
   * @param account the account whose change was refused
   */
  public UnlinkedEmployeeAccountException(UserId account) {
    super(
        "Account %s holds the Employee role and is linked to no employee"
            .formatted(account.value()));
    this.account = account;
  }

  /**
   * @return the account whose change was refused
   */
  public UserId account() {
    return account;
  }
}
