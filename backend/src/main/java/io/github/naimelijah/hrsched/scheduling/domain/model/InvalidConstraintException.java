package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when a constraint would be configured with a meaningless threshold, or a constraint set
 * would be built that no set may be: below its first version, or holding two rules of one kind.
 */
public class InvalidConstraintException extends RuntimeException {

  /**
   * @param detail what is wrong, as a sentence that states the rule and not the value refused: it
   *     is the 400 response's detail, and a caller is not told back what it sent (ADR-024)
   */
  public InvalidConstraintException(String detail) {
    super(detail);
  }
}
