package io.github.naimelijah.hrsched.workforce.domain.model;

/**
 * Raised when a withdrawal names unavailability or a preference the employee never declared.
 *
 * <p>A declaration has no identity of its own, so a withdrawal names it by what it says (ADR-040);
 * this is the answer when nothing said that. The message carries no span on purpose: a span says
 * when somebody cannot work, and a message travels further than the record it describes.
 */
public class UnknownDeclarationException extends RuntimeException {

  public UnknownDeclarationException() {
    super("Nothing declared matches what was asked to be withdrawn");
  }
}
