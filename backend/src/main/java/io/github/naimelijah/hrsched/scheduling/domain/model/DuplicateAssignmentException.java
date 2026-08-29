package io.github.naimelijah.hrsched.scheduling.domain.model;

/** Raised when the same employee would be assigned to the same shift twice. */
public class DuplicateAssignmentException extends RuntimeException {

  public DuplicateAssignmentException(Assignment assignment) {
    super("Already assigned: " + assignment);
  }
}
