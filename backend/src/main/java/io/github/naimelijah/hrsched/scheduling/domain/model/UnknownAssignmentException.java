package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when a roster is asked to remove an assignment it does not carry.
 *
 * <p>A refusal rather than a silent no-op, because "unassign Ada from the Monday shift" succeeding
 * when Ada was never on it tells a Scheduler that a change happened which did not.
 */
public class UnknownAssignmentException extends RuntimeException {

  /**
   * @param roster the roster that does not carry the assignment
   * @param assignment the employee and shift it does not carry; the message names them by
   *     identifier, and the 404 response replaces it with a constant, so it reaches no caller
   */
  public UnknownAssignmentException(RosterId roster, Assignment assignment) {
    super(
        "Roster %s does not assign employee %s to shift %s"
            .formatted(roster.value(), assignment.employee().value(), assignment.shift().value()));
  }
}
