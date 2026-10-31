package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when the organisation being acted on has no version of its working-time rules by the given
 * number.
 */
public class UnknownConstraintVersionException extends RuntimeException {

  /**
   * @param version the number that was asked for; the message carries it, and the 404 response
   *     replaces it with a constant, so it reaches no caller
   */
  public UnknownConstraintVersionException(int version) {
    super("No such constraint version: " + version);
  }
}
