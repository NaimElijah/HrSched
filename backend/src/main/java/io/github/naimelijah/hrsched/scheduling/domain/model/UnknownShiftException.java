package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when an operation names a shift the organisation does not have.
 *
 * <p>Reported as absence rather than as a permission failure, because a shift belonging to another
 * organisation and a shift that never existed must be indistinguishable (ADR-009).
 */
public class UnknownShiftException extends RuntimeException {

  public UnknownShiftException(ShiftId shift) {
    super("No such shift %s".formatted(shift.value()));
  }
}
