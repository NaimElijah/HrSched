package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when the organisation being acted on has no version of its working-time rules by the given
 * number.
 */
public class UnknownConstraintVersionException extends RuntimeException {

  public UnknownConstraintVersionException(int version) {
    super("No such constraint version: " + version);
  }
}
