package io.github.naimelijah.hrsched.access.domain.model;

/**
 * Raised when a sign-in name is blank, longer than a name may be, or carries a character no name
 * can hold.
 *
 * <p>Carries no field and quotes nothing. A sign-in name is personal data, and a refusal is exactly
 * the moment it would otherwise reach a log: the message has to be the same whatever was submitted,
 * so that the exception can be logged without redacting it at every catch site.
 */
public class InvalidSignInNameException extends RuntimeException {

  public InvalidSignInNameException() {
    super(
        "A sign-in name must not be blank, must not be longer than the permitted maximum, and must"
            + " not contain a NUL character");
  }
}
