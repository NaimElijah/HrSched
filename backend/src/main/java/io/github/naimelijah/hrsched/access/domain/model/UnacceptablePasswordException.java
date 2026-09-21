package io.github.naimelijah.hrsched.access.domain.model;

import java.util.Objects;

/**
 * Raised when a password somebody chose does not satisfy the policy.
 *
 * <p>Carries the rule that was broken and never the password. The rule is told to the caller,
 * because somebody choosing a password has to know what would satisfy it, and saying so reveals
 * nothing: the rules are the same for everybody and could be discovered by trying.
 */
public class UnacceptablePasswordException extends RuntimeException {

  private final PasswordRejection reason;

  /**
   * @param reason the rule the password broke
   */
  public UnacceptablePasswordException(PasswordRejection reason) {
    super("The password does not satisfy the policy: " + reason);
    this.reason = Objects.requireNonNull(reason, "reason must not be null");
  }

  /**
   * @return the rule the password broke
   */
  public PasswordRejection reason() {
    return reason;
  }
}
