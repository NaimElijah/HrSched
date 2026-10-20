package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintViolation;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterScore;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shortfall;
import java.util.List;
import java.util.Objects;

/**
 * What publishing a draft would say now, without publishing it: every rule it breaks, and every
 * shift short of people (ADR-042). Beside that, how well it serves the organisation's objectives
 * (ADR-010).
 *
 * <p>Advisory. It was judged without the publication lock, so a publication made since can change
 * the answer; publishing judges again, under the lock, and decides.
 *
 * @param roster which draft
 * @param constraintVersion the version of the working-time rules it was judged against
 * @param violations every rule it breaks, exactly as publication would list them; empty if none
 * @param shortfalls every shift in its period with fewer people on it than it requires
 * @param score how it measures on the organisation's objectives, and what that comes to under the
 *     organisation's weights (ADR-010); it never decides whether the draft may be published
 */
public record RosterAssessment(
    RosterId roster,
    int constraintVersion,
    List<ConstraintViolation> violations,
    List<Shortfall> shortfalls,
    RosterScore score) {

  public RosterAssessment {
    Objects.requireNonNull(roster, "roster must not be null");
    Objects.requireNonNull(score, "score must not be null");
    violations = List.copyOf(violations);
    shortfalls = List.copyOf(shortfalls);
  }
}
