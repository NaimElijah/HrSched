package io.github.naimelijah.hrsched.workforce.domain.model;

/**
 * Raised when unavailability would overlap a span the employee has already declared.
 *
 * <p>The message names neither span on purpose: one of them is an absence already on record, a span
 * says when somebody cannot work, and a message travels further than the record it describes.
 */
public class OverlappingUnavailabilityException extends RuntimeException {

  public OverlappingUnavailabilityException() {
    super("Unavailability overlaps a span already declared");
  }
}
