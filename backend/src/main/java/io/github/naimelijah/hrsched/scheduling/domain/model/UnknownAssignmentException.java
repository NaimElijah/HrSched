package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when a roster is asked to remove an assignment it does not carry.
 *
 * <p>A refusal rather than a silent no-op, because "unassign Ada from the Monday shift" succeeding
 * when Ada was never on it tells a Scheduler that a change happened which did not.
 */
public class UnknownAssignmentException extends RuntimeException {

  public UnknownAssignmentException(RosterId roster, Assignment assignment) {
    super(
        "Roster %s does not assign employee %s to shift %s"
            .formatted(roster.value(), assignment.employee().value(), assignment.shift().value()));
  }
}
