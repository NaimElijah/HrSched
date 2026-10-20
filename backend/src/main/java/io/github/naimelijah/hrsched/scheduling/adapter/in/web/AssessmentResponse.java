package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.application.port.in.RosterAssessment;
import java.util.List;
import java.util.UUID;

/**
 * What publishing a draft would say now (#205). The violations are shaped exactly as a publication
 * refusal lists them, so a client renders both the same way. Beside them, how the draft scores on
 * the organisation's objectives, which never decides whether it may be published.
 *
 * @param roster which draft
 * @param constraintVersion the version of the working-time rules it was judged against
 * @param violations every rule it breaks; empty if none
 * @param shortfalls every shift in its period with fewer people on it than it requires
 * @param objectives how it measures on each objective, with the organisation's weight on each
 *     (ADR-010)
 * @param weightedTotal the sum of each weighted objective's weight times its measure; lower is
 *     better, and an organisation weighing nothing scores every draft zero
 */
public record AssessmentResponse(
    UUID roster,
    int constraintVersion,
    List<ViolationResponse> violations,
    List<ShortfallResponse> shortfalls,
    List<ObjectiveResponse> objectives,
    long weightedTotal) {

  /**
   * @param assessment the use case's answer
   * @return the same answer, in the API's terms
   */
  static AssessmentResponse from(RosterAssessment assessment) {
    return new AssessmentResponse(
        assessment.roster().value(),
        assessment.constraintVersion(),
        assessment.violations().stream().map(ViolationResponse::from).toList(),
        assessment.shortfalls().stream().map(ShortfallResponse::from).toList(),
        ObjectiveResponse.listed(assessment.score()),
        assessment.score().weightedTotal());
  }
}
