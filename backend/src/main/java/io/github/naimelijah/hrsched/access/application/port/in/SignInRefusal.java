package io.github.naimelijah.hrsched.access.application.port.in;

/**
 * Why a sign-in was refused.
 *
 * <p><strong>For the log and the metric, never for the caller.</strong> Whoever submitted the
 * credentials is told the same thing in every one of these cases: telling them which one would say
 * whether the name exists, whether the account is disabled, and whether guessing has been going on
 * - each of which is a fact an attacker is trying to learn.
 */
public enum SignInRefusal {

  /** No account holds that sign-in name, or the name is not one an account could hold. */
  UNKNOWN_ACCOUNT,

  /** The account exists and the password was wrong. */
  WRONG_PASSWORD,

  /** The account exists but has been disabled. */
  DISABLED,

  /** The account is locked after too many consecutive failures. */
  LOCKED
}
