package io.github.naimelijah.hrsched.scheduling.domain.model;

import java.util.Objects;

/**
 * What a roster's measures come to under one organisation's weights (ADR-010).
 *
 * <p>Two rosters scored under the same weights compare by their weighted totals, and the lower is
 * better. An organisation that weighs nothing scores every roster zero, so none is preferred.
 *
 * @param measures how the roster measures on each objective
 * @param weights how much the organisation cares about each
 */
public record RosterScore(RosterMeasures measures, ObjectiveWeights weights) {

  public RosterScore {
    Objects.requireNonNull(measures, "measures must not be null");
    Objects.requireNonNull(weights, "weights must not be null");
  }

  /**
   * @return the sum of each weighted objective's weight times its measure; an objective with no
   *     weight adds nothing
   * @throws ArithmeticException if the total would not fit in a {@code long}, rather than wrapping
   *     round to a total that ranks the roster wrongly
   */
  public long weightedTotal() {
    return weights.weights().entrySet().stream()
        .mapToLong(
            weighted ->
                Math.multiplyExact(
                    (long) weighted.getValue(), measures.measureOf(weighted.getKey())))
        .reduce(0L, Math::addExact);
  }
}
