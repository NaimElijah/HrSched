package io.github.naimelijah.hrsched.access.domain.model;

/**
 * Raised when an organisation name is blank, or carries a character no name can hold. The message
 * never repeats the name, since it becomes a 400's detail (ADR-024).
 */
public class InvalidOrganisationNameException extends RuntimeException {

  /** Creates the exception with a fixed message that states the rule and never quotes the name. */
  public InvalidOrganisationNameException() {
    super(
        "An organisation must have a name that is not blank and contains no NUL character and no"
            + " unpaired surrogate");
  }
}
