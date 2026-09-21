package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.UserId;

/**
 * Raised when an Admin aims the administrative password reset at their own account.
 *
 * <p>Changing your own password takes the current one, and that is not ceremony: it is what stops a
 * stolen access token being turned into a password its owner does not know, locking them out of
 * their own account while the thief keeps it. An Admin who could reset themselves here would have
 * that protection and be able to step around it in the same session.
 *
 * <p>It costs them nothing. An Admin who is signed in can change their password at {@code
 * /api/auth/password}, and one who is locked out cannot reach this endpoint at all.
 */
public class CannotResetOwnPasswordException extends RuntimeException {

  private final UserId account;

  /**
   * @param account the Admin who aimed the reset at themselves
   */
  public CannotResetOwnPasswordException(UserId account) {
    super("Account %s may not reset its own password here".formatted(account.value()));
    this.account = account;
  }

  /**
   * @return the Admin who aimed the reset at themselves
   */
  public UserId account() {
    return account;
  }
}
