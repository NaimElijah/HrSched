package io.github.naimelijah.hrsched.access.application.port.in;

/**
 * Why a change of one's own password was refused for its current password.
 *
 * <p><strong>For the log and the metric, never for the caller.</strong> Both are answered alike, as
 * sign-in's refusals are ({@link SignInRefusal}): telling a locked account from a wrong password
 * would tell whoever holds the session that guessing has been going on, and that a guess now would
 * be wasted. An operator needs the difference, because a stream of refusals while locked is
 * somebody still guessing behind the lock.
 */
public enum CurrentPasswordRefusal {

  /** The current password submitted was not the account's. Counted as a failed sign-in. */
  WRONG_PASSWORD,

  /** The account is locked, so no password was accepted and nothing was counted. */
  LOCKED
}
