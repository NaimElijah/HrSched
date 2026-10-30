package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when a constraint would be configured with a meaningless threshold, or a constraint set
 * would be built that no set may be: below its first version, or holding two rules of one kind.
 */
public class InvalidConstraintException extends RuntimeException {

  public InvalidConstraintException(String detail) {
    super(detail);
  }
}
