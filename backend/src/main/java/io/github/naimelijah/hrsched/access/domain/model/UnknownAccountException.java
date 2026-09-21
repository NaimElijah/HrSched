package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.UserId;

/**
 * Raised when an account named in a request does not exist in the organisation acting on it.
 *
 * <p>One exception for two situations on purpose: an account that was never created and one
 * belonging to another organisation are indistinguishable here, and they have to be. Telling them
 * apart would let an Admin discover that a colleague of theirs holds an account with another
 * customer, one identifier at a time.
 */
public class UnknownAccountException extends RuntimeException {

  private final UserId account;

  /**
   * @param account the account that was named
   */
  public UnknownAccountException(UserId account) {
    super("No account %s in this organisation".formatted(account.value()));
    this.account = account;
  }

  /**
   * @return the account that was named
   */
  public UserId account() {
    return account;
  }
}
