package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.Objective;
import java.util.EnumMap;
import java.util.Map;

/**
 * How an organisation wants to weigh what makes one legal roster better than another (ADR-010).
 *
 * <p>Every field is optional: a null means that objective is not weighed. There is no default
 * weight anywhere in this codebase, for the reason there is no default working-time threshold:
 * whoever configures an organisation decides what matters to it, not a developer.
 *
 * @param fairness the weight on the heaviest unpopular load anybody carries
 * @param preferences the weight on the stated preferences a roster goes against
 * @param disruption the weight on the assignments a roster changes from those it supersedes
 */
public record ObjectiveWeightsRequest(Integer fairness, Integer preferences, Integer disruption) {

  /**
   * @return each weighted objective and its weight, omitting every objective left unset; whether a
   *     weight is acceptable is the domain's to decide
   */
  Map<Objective, Integer> toWeights() {
    Map<Objective, Integer> weights = new EnumMap<>(Objective.class);
    if (fairness != null) {
      weights.put(Objective.FAIRNESS, fairness);
    }
    if (preferences != null) {
      weights.put(Objective.PREFERENCES, preferences);
    }
    if (disruption != null) {
      weights.put(Objective.DISRUPTION, disruption);
    }
    return weights;
  }
}
