package io.github.naimelijah.hrsched.scheduling.domain.model;

/** Raised when a roster is changed in a way its current status forbids. */
public class RosterNotEditableException extends RuntimeException {

  public RosterNotEditableException(RosterId id, RosterStatus status, String detail) {
    super("Roster %s is %s: %s".formatted(id.value(), status, detail));
  }
}
