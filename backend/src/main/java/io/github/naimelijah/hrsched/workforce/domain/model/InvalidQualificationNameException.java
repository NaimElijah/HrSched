package io.github.naimelijah.hrsched.workforce.domain.model;

/** Raised when a qualification name is blank. */
public class InvalidQualificationNameException extends RuntimeException {

  /**
   * Builds the refusal with a fixed message that quotes no name, so it can be the detail of a 400
   * as it stands (ADR-024).
   */
  public InvalidQualificationNameException() {
    super("A qualification must have a name that is not blank");
  }
}
