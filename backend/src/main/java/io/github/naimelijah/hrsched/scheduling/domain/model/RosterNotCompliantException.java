package io.github.naimelijah.hrsched.scheduling.domain.model;

import java.util.List;

/**
 * Raised when a roster would break any rule: its organisation's working-time rules, or the ones
 * that hold whatever those say - one place at a time (R3, R35), the qualifications a shift requires
 * (R4), declared unavailability (R18), and belonging to the organisation (R5). Every breach is
 * carried, not the first found (ADR-042).
 *
 * <p>There is no override. A roster that cannot be published legally is not published, and cover is
 * arranged by producing one that is legal, or by leaving the shift uncovered so the business can
 * see the real problem.
 */
public class RosterNotCompliantException extends RuntimeException {

  private final transient List<ConstraintViolation> violations;

  public RosterNotCompliantException(List<ConstraintViolation> violations) {
    super("The roster breaks %d rule(s)".formatted(violations.size()));
    this.violations = List.copyOf(violations);
  }

  public List<ConstraintViolation> violations() {
    return violations;
  }
}
