package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * How much one organisation cares about each objective, relative to the others (Q13, ADR-010).
 *
 * <p>Configuration, never a constant: there is no default weight anywhere, so an organisation that
 * has weighted nothing has every roster score zero, which is how rosters were judged before
 * objectives existed. An objective left out is not weighed, as a working-time rule left out is not
 * enforced.
 *
 * <p>Not versioned, unlike the constraint set. A published roster keeps the rules that approved it
 * because legality is judged once and must stay true (Q9); nothing is ever judged against a weight,
 * so revising them replaces them.
 *
 * @param organisation whose weights these are
 * @param weights each weighted objective and its weight, which is at least one (R40); an objective
 *     absent is not weighed, and none at all is allowed
 */
public record ObjectiveWeights(OrganisationId organisation, Map<Objective, Integer> weights) {

  public ObjectiveWeights {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(weights, "weights must not be null");
    // An EnumMap, so the weights list in the objectives' own order wherever they are shown.
    Map<Objective, Integer> copy = new EnumMap<>(Objective.class);
    weights.forEach(
        (objective, weight) -> {
          Objects.requireNonNull(objective, "an objective must not be null");
          if (weight == null || weight < 1) {
            throw new InvalidObjectiveWeightException(objective);
          }
          copy.put(objective, weight);
        });
    weights = Collections.unmodifiableMap(copy);
  }

  /**
   * @param organisation whose weights these are
   * @return weights for an organisation that has weighted nothing
   */
  public static ObjectiveWeights none(OrganisationId organisation) {
    return new ObjectiveWeights(organisation, Map.of());
  }

  /**
   * @param objective an objective
   * @return its weight, or empty if this organisation does not weigh it
   */
  public Optional<Integer> weightOf(Objective objective) {
    return Optional.ofNullable(weights.get(Objects.requireNonNull(objective, "objective")));
  }
}
