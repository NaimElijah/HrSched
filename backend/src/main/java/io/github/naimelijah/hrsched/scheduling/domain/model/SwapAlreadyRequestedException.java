package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when an assignment already has a swap request waiting on a Scheduler (R32).
 *
 * <p>One per assignment, whoever it asks. Two pending requests for one shift would let two
 * Schedulers approve two different handovers of it, and the person on it could not tell which of
 * their asks was the live one.
 */
public class SwapAlreadyRequestedException extends RuntimeException {

  /**
   * Builds the refusal. The message names the shift and the roster by identifier, never the people
   * involved, and is the 409 response's detail.
   *
   * @param roster the roster the earlier request was raised against
   * @param shift the shift it asks to hand over
   */
  public SwapAlreadyRequestedException(RosterId roster, ShiftId shift) {
    super(
        "A swap of shift %s on roster %s is already waiting on a Scheduler"
            .formatted(shift.value(), roster.value()));
  }
}
