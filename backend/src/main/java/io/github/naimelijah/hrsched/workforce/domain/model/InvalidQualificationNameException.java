package io.github.naimelijah.hrsched.workforce.domain.model;

/** Raised when a qualification name is blank. */
public class InvalidQualificationNameException extends RuntimeException {

  public InvalidQualificationNameException() {
    super("A qualification must have a name that is not blank");
  }
}
