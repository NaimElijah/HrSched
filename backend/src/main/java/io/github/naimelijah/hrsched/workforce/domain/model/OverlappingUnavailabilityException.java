package io.github.naimelijah.hrsched.workforce.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;

/** Raised when unavailability would overlap a span the employee has already declared. */
public class OverlappingUnavailabilityException extends RuntimeException {

  public OverlappingUnavailabilityException(TimeRange declared, TimeRange existing) {
    super("Unavailability %s overlaps already declared %s".formatted(declared, existing));
  }
}
