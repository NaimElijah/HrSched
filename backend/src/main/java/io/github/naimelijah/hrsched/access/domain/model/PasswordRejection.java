package io.github.naimelijah.hrsched.access.domain.model;

/**
 * Why a chosen password was not accepted.
 *
 * <p>Unlike a sign-in refusal, this one <em>is</em> told to the caller: somebody choosing a
 * password needs to know which rule they broke, and telling them reveals nothing an attacker does
 * not already know, since the rules are the same for everybody and could be discovered by trying.
 */
public enum PasswordRejection {

  /** Shorter than the policy's floor, counted in code points. */
  TOO_SHORT,

  /** Longer than the hasher can read, counted in UTF-8 bytes. */
  TOO_LONG,

  /** The same as the password already in use, so changing it would change nothing. */
  SAME_AS_CURRENT
}
