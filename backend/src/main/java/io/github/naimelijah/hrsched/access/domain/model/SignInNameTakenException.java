package io.github.naimelijah.hrsched.access.domain.model;

/**
 * Raised when an account is saved under a sign-in name another account already holds.
 *
 * <p>Quotes nothing. The name that collided is personal data, and it belongs to somebody other than
 * whoever triggered this, so a message carrying it would both reach a log and tell the caller that
 * a particular person has an account here.
 *
 * <p>Uniqueness is platform-wide rather than per organisation, so this can be raised by a collision
 * with an account the caller may not see. That is deliberate: the alternative is an ambiguous
 * sign-in.
 */
public class SignInNameTakenException extends RuntimeException {

  /**
   * @param cause the integrity violation the database reported
   */
  public SignInNameTakenException(Throwable cause) {
    super("That sign-in name is already in use", cause);
  }
}
