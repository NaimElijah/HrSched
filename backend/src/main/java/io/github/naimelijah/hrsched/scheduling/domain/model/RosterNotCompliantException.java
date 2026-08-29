package io.github.naimelijah.hrsched.scheduling.domain.model;

import java.util.List;

/**
 * Raised when a roster would break its organisation working-time rules.
 *
 * <p>There is no override. A roster that cannot be published legally is not published, and cover is
 * arranged by producing one that is legal, or by leaving the shift uncovered so the business can
 * see the real problem.
 */
public class RosterNotCompliantException extends RuntimeException {

  private final transient List<ConstraintViolation> violations;

  public RosterNotCompliantException(List<ConstraintViolation> violations) {
    super("The roster breaks %d working-time rule(s)".formatted(violations.size()));
    this.violations = List.copyOf(violations);
  }

  public List<ConstraintViolation> violations() {
    return violations;
  }
}
