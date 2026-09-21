package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.access.domain.model.PasswordRejection;
import java.util.Objects;

/**
 * What came of an attempt to change one's own password.
 *
 * <p>Returned rather than thrown, for the same reason sign-in's refusal is: a wrong current
 * password counts as a failed attempt, and a thrown refusal would roll that count back (ADR-023).
 * Without it, this endpoint would be a way to guess at a password with no lockout at all.
 *
 * <p>The two refusals are deliberately different kinds of thing, and answer differently. A wrong
 * current password says nothing about the new one and is nobody's business but the caller's; a
 * rejected new password names the rule it broke, because somebody choosing a password has to be
 * told what would satisfy it.
 */
public sealed interface ChangePasswordOutcome {

  /**
   * The password was changed, every other session was ended, and a new one was begun.
   *
   * <p>A new session comes back because changing a password revokes every session of the account,
   * including the one that asked — signing the caller out of their own password change would be a
   * strange way to reward them for it.
   *
   * @param session the tokens for the session that replaces them
   */
  record Changed(IssuedSession session) implements ChangePasswordOutcome {

    public Changed {
      Objects.requireNonNull(session, "session must not be null");
    }
  }

  /**
   * The current password submitted was not the account's, so nothing changed. Counted as a failed
   * sign-in, and capable of locking the account like any other.
   */
  record CurrentPasswordRejected() implements ChangePasswordOutcome {}

  /**
   * The new password does not satisfy the policy.
   *
   * @param reason the rule it broke, which the caller is told
   */
  record NewPasswordRejected(PasswordRejection reason) implements ChangePasswordOutcome {

    public NewPasswordRejected {
      Objects.requireNonNull(reason, "reason must not be null");
    }
  }
}
