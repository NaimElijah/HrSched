package io.github.naimelijah.hrsched.scheduling.domain.model;

/** Raised when no roster with the given identity exists in the organisation being acted on. */
public class UnknownRosterException extends RuntimeException {

  /**
   * @param id the identity that was asked for; the message carries it, and the 404 response
   *     replaces it with a constant, so it reaches no caller
   */
  public UnknownRosterException(RosterId id) {
    super("No such roster: " + id.value());
  }
}
