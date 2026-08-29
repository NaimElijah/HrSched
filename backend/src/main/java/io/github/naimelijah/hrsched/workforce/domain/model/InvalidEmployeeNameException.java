package io.github.naimelijah.hrsched.workforce.domain.model;

/**
 * Raised when an employee name is absent or blank.
 *
 * <p>A domain exception, deliberately unchecked and free of any framework type: adapters translate
 * it into whatever their transport considers a client error.
 */
public class InvalidEmployeeNameException extends RuntimeException {

  public InvalidEmployeeNameException() {
    super("An employee must have a name that is not blank");
  }
}
