package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when an assignment already has a swap request waiting on a Scheduler (R32).
 *
 * <p>One per assignment, whoever it asks. Two pending requests for one shift would let two
 * Schedulers approve two different handovers of it, and the person on it could not tell which of
 * their asks was the live one.
 */
public class SwapAlreadyRequestedException extends RuntimeException {

  public SwapAlreadyRequestedException(RosterId roster, ShiftId shift) {
    super(
        "A swap of shift %s on roster %s is already waiting on a Scheduler"
            .formatted(shift.value(), roster.value()));
  }
}
