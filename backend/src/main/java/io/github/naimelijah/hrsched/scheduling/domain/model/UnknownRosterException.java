package io.github.naimelijah.hrsched.scheduling.domain.model;

/** Raised when no roster with the given identity exists in the organisation being acted on. */
public class UnknownRosterException extends RuntimeException {

  public UnknownRosterException(RosterId id) {
    super("No such roster: " + id.value());
  }
}
