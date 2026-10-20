package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.Objective;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterScore;
import java.util.Arrays;
import java.util.List;

/**
 * How a draft measures on one objective, and the weight the organisation puts on it (ADR-010).
 *
 * @param objective which objective: {@code FAIRNESS}, {@code PREFERENCES} or {@code DISRUPTION}
 * @param measure the count it comes to; lower is better
 * @param weight the organisation's weight on it, or null when it is not weighed and so adds nothing
 *     to the total
 */
public record ObjectiveResponse(String objective, int measure, Integer weight) {

  /**
   * @param score a draft's score
   * @return one entry per objective, weighted or not, in the objectives' own order
   */
  static List<ObjectiveResponse> listed(RosterScore score) {
    return Arrays.stream(Objective.values())
        .map(
            objective ->
                new ObjectiveResponse(
                    objective.name(),
                    score.measures().measureOf(objective),
                    score.weights().weightOf(objective).orElse(null)))
        .toList();
  }
}
