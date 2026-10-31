package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when the same employee would be assigned to the same shift twice.
 *
 * <p>The message names neither of them: it becomes the refusal's detail, and a caller is not told
 * back what it sent.
 */
public class DuplicateAssignmentException extends RuntimeException {

  public DuplicateAssignmentException() {
    super("That employee is already assigned to that shift on this roster.");
  }
}
