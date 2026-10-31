package io.github.naimelijah.hrsched.workforce.domain.model;

/**
 * Raised when an employee name is absent or blank.
 *
 * <p>A domain exception, deliberately unchecked and free of any framework type: adapters translate
 * it into whatever their transport considers a client error.
 */
public class InvalidEmployeeNameException extends RuntimeException {

  /**
   * Builds the refusal with a fixed message that quotes no name, so it can be the detail of a 400
   * as it stands (ADR-024).
   */
  public InvalidEmployeeNameException() {
    super("An employee must have a name that is not blank");
  }
}
