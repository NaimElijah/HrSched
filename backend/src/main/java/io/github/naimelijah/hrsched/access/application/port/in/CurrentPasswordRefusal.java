package io.github.naimelijah.hrsched.access.application.port.in;

/**
 * Why a change of one's own password was refused without its current password being accepted: it
 * was wrong, or the account could take no password at all.
 *
 * <p><strong>For the log and the metric, never for the caller.</strong> All are answered alike, as
 * sign-in's refusals are ({@link SignInRefusal}): telling a locked account from a wrong password
 * would tell whoever holds the session that guessing has been going on, and that a guess now would
 * be wasted. An operator needs the difference, because a stream of refusals while locked is
 * somebody still guessing behind the lock. A disable is answered alike too, and the caller loses
 * nothing by it: the disable ended every session, so their very next request is answered 401.
 */
public enum CurrentPasswordRefusal {

  /** The current password submitted was not the account's. Counted as a failed sign-in. */
  WRONG_PASSWORD,

  /** The account is locked, so no password was accepted and nothing was counted. */
  LOCKED,

  /**
   * The account was disabled after the request was authenticated, and before it was held: a race
   * with an Admin's disable, since a disabled account's requests are otherwise refused before they
   * get here. No password was accepted and nothing was counted, so no session is begun after the
   * disable ended every other (R17, ADR-041).
   */
  DISABLED
}
