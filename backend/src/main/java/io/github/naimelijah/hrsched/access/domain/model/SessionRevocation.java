package io.github.naimelijah.hrsched.access.domain.model;

/**
 * Why a session ended before its own expiry.
 *
 * <p>An enum rather than a flag, because "was this session revoked?" and "why" are different
 * questions and only one of them is answerable by a boolean. Reuse detection in particular has to
 * be countable on its own: it is the signal that somebody other than the owner is holding a token.
 */
public enum SessionRevocation {

  /** Somebody signed out. */
  SIGNED_OUT,

  /**
   * A refresh token was presented after it had already been replaced, so one holder is not the
   * owner and neither can be told apart.
   */
  REFRESH_REUSED,

  /** The account's password changed, so every session opened under the old one ends. */
  PASSWORD_CHANGED,

  /** The account was disabled. */
  ACCOUNT_DISABLED,

  /** An administrator reset the account's password. */
  PASSWORD_RESET
}
