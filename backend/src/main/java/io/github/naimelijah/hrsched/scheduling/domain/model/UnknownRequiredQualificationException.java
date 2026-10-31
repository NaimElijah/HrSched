package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when a shift demands a qualification the organisation has never defined.
 *
 * <p>Such a shift is not merely wrong, it is quietly unfillable: nobody can hold the qualification,
 * so every generated roster is short of headcount with nothing to explain why. Refusing it at
 * definition time turns a permanent silent gap into an immediate error.
 *
 * <p>The message does not list the identifiers refused: it becomes the refusal's detail, and a
 * caller is not told back what it sent (ADR-024).
 */
public class UnknownRequiredQualificationException extends RuntimeException {

  /** Builds the refusal with a constant message, which is the 400 response's detail. */
  public UnknownRequiredQualificationException() {
    super("A shift cannot require a qualification this organisation has not defined");
  }
}
