package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when work is put on a roster whose period does not cover it.
 *
 * <p>Generation cannot produce this: it only ever assigns shifts it found inside the roster's own
 * period. Assigning by hand could, and the consequence is the state R11 exists to prevent — because
 * supersession compares roster periods, a December shift carried by an October roster is governed
 * by a roster that does not claim to cover December, and the roster that genuinely covers December
 * will not supersede it. Two published rosters would then be authoritative for the same moment.
 */
public class ShiftOutsideRosterPeriodException extends RuntimeException {

  /**
   * Builds the refusal. The message names the shift and the roster by identifier, and is the 422
   * response's detail.
   *
   * @param roster the roster whose period does not cover the shift
   * @param shift the shift that was to be put on it
   */
  public ShiftOutsideRosterPeriodException(RosterId roster, ShiftId shift) {
    super(
        "Shift %s falls outside the period roster %s covers"
            .formatted(shift.value(), roster.value()));
  }
}
