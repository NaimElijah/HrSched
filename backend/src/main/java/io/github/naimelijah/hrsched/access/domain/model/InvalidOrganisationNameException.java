package io.github.naimelijah.hrsched.access.domain.model;

/** Raised when an organisation name is blank. */
public class InvalidOrganisationNameException extends RuntimeException {

  public InvalidOrganisationNameException() {
    super("An organisation must have a name that is not blank");
  }
}
