package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when a roster cannot be checked against the rules because one of the shifts it assigns
 * cannot be resolved.
 *
 * <p>This is deliberately a refusal rather than an omission. Skipping work that cannot be verified
 * would let a roster be published with hours nobody counted, which is the one thing publication is
 * supposed to prevent.
 *
 * <p>It carries whether it refused a publication. The same check refuses an assignment, an
 * assessment of a draft and a read of somebody's own schedule, and only the first is a refused
 * publication (#134). Where it is raised does not know which it is refusing, so publication marks
 * it with {@link #refusingPublication()}.
 */
public class RosterNotVerifiableException extends RuntimeException {

  private final RosterId roster;
  private final ShiftId shift;
  private final boolean refusedPublication;

  /** Raised by any check of the roster; see {@link #refusingPublication()} for a publication. */
  public RosterNotVerifiableException(RosterId roster, ShiftId shift) {
    this(roster, shift, false, null);
  }

  private RosterNotVerifiableException(
      RosterId roster, ShiftId shift, boolean refusedPublication, Throwable cause) {
    super(
        "Roster %s assigns shift %s, which cannot be resolved, so it cannot be checked against the rules"
            .formatted(roster.value(), shift.value()),
        cause);
    this.roster = roster;
    this.shift = shift;
    this.refusedPublication = refusedPublication;
  }

  /**
   * @return the same refusal, saying it refused publishing the roster, and caused by this one
   */
  public RosterNotVerifiableException refusingPublication() {
    return new RosterNotVerifiableException(roster, shift, true, this);
  }

  /**
   * @return whether it refused a publication, rather than an edit or a read that checks the roster
   */
  public boolean refusedPublication() {
    return refusedPublication;
  }
}
