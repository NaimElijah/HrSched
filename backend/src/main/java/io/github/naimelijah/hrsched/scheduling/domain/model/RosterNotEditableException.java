package io.github.naimelijah.hrsched.scheduling.domain.model;

/** Raised when a roster is changed in a way its current status forbids. */
public class RosterNotEditableException extends RuntimeException {

  /**
   * @param id the roster refused
   * @param status the status that forbids the change
   * @param detail why that status forbids it, as a fixed sentence naming no person: the message
   *     carries it into the 409 response's detail
   */
  public RosterNotEditableException(RosterId id, RosterStatus status, String detail) {
    super("Roster %s is %s: %s".formatted(id.value(), status, detail));
  }
}
