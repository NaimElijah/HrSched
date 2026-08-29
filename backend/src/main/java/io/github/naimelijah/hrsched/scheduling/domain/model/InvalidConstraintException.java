package io.github.naimelijah.hrsched.scheduling.domain.model;

/** Raised when a constraint would be configured with a meaningless threshold. */
public class InvalidConstraintException extends RuntimeException {

  public InvalidConstraintException(String detail) {
    super(detail);
  }
}
