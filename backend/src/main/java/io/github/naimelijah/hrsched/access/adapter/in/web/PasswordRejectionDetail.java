package io.github.naimelijah.hrsched.access.adapter.in.web;

import io.github.naimelijah.hrsched.access.domain.model.PasswordPolicy;
import io.github.naimelijah.hrsched.access.domain.model.PasswordRejection;

/**
 * The sentence a refused password is answered with, naming what would satisfy the policy.
 *
 * <p>One place for it, because two refusals give it: a password somebody chose for themselves, in
 * {@link AuthController}, and a temporary one an Admin or the operator chose, in {@link
 * AccessExceptionHandler}. The rule is the same for everybody and could be found by trying, so
 * saying it reveals nothing; the password itself is never in it.
 */
final class PasswordRejectionDetail {

  private PasswordRejectionDetail() {}

  /**
   * @param reason the rule the password broke
   * @param policy the policy it was judged against, for the numbers the sentence names
   * @return a sentence saying what a password must be instead
   */
  static String of(PasswordRejection reason, PasswordPolicy policy) {
    return switch (reason) {
      case TOO_SHORT ->
          "A password must be at least %d characters long.".formatted(policy.minimumLength());
      case TOO_LONG -> "A password must be at most %d bytes long.".formatted(policy.maximumBytes());
      case SAME_AS_CURRENT -> "The new password must be different from the current one.";
    };
  }
}
