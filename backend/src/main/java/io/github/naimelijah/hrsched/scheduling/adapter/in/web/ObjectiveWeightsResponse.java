package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.Objective;
import io.github.naimelijah.hrsched.scheduling.domain.model.ObjectiveWeights;

/**
 * An organisation's objective weights, each null when that objective is not weighed (ADR-010).
 *
 * <p>Echoed whole because a revision replaces them whole: a caller changing one weight resends the
 * others, which it can only do if it can read them back.
 *
 * @param fairness the weight on the heaviest unpopular load anybody carries, or null
 * @param preferences the weight on the stated preferences a roster goes against, or null
 * @param disruption the weight on the assignments a roster changes from those it supersedes, or
 *     null
 */
public record ObjectiveWeightsResponse(Integer fairness, Integer preferences, Integer disruption) {

  static ObjectiveWeightsResponse from(ObjectiveWeights weights) {
    return new ObjectiveWeightsResponse(
        weights.weightOf(Objective.FAIRNESS).orElse(null),
        weights.weightOf(Objective.PREFERENCES).orElse(null),
        weights.weightOf(Objective.DISRUPTION).orElse(null));
  }
}
