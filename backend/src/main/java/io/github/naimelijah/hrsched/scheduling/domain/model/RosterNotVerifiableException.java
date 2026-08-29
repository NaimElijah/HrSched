package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when a roster cannot be checked against the rules because one of the shifts it assigns
 * cannot be resolved.
 *
 * <p>This is deliberately a refusal rather than an omission. Skipping work that cannot be verified
 * would let a roster be published with hours nobody counted, which is the one thing publication is
 * supposed to prevent.
 */
public class RosterNotVerifiableException extends RuntimeException {

  public RosterNotVerifiableException(RosterId roster, ShiftId shift) {
    super(
        "Roster %s assigns shift %s, which cannot be resolved, so it cannot be checked against the rules"
            .formatted(roster.value(), shift.value()));
  }
}
