package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.UserId;

/**
 * Raised when a change would leave an organisation with no active Admin.
 *
 * <p>An organisation in that state is unrecoverable from inside: only an Admin may create accounts
 * or restore one, and the platform operator is refused everything inside a tenant (ADR-030), so
 * there is nobody left who could undo it. The refusal is therefore a rule rather than a warning,
 * and it applies to demoting the last Admin as much as to disabling them.
 *
 * <p>Carries the account the change was aimed at, so a refusal can be traced without naming
 * anybody.
 */
public class LastActiveAdminException extends RuntimeException {

  private final UserId account;

  /**
   * @param account the account whose change was refused
   */
  public LastActiveAdminException(UserId account) {
    super("Account %s is the last active Admin of its organisation".formatted(account.value()));
    this.account = account;
  }

  /**
   * @return the account whose change was refused
   */
  public UserId account() {
    return account;
  }
}
