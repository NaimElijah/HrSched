package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when a shift would require fewer than one person.
 *
 * <p>The message states the rule and not the number refused: it becomes the refusal's detail, and a
 * caller is not told back what it sent (ADR-024).
 */
public class InvalidHeadcountException extends RuntimeException {

  public InvalidHeadcountException() {
    super("A shift must require at least one person");
  }
}
