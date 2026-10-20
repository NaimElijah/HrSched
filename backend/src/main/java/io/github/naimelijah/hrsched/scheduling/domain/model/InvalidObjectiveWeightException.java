package io.github.naimelijah.hrsched.scheduling.domain.model;

import java.util.Locale;

/**
 * A weight that is not a whole number of at least one (R40).
 *
 * <p>The message is fixed and repeats nothing that was sent (ADR-024). Leaving an objective
 * unweighted is how it is switched off, so zero is never needed.
 */
public class InvalidObjectiveWeightException extends RuntimeException {

  /**
   * @param objective the objective given the weight
   */
  public InvalidObjectiveWeightException(Objective objective) {
    super(
        "The %s weight must be a whole number of at least one; leave it out to weigh nothing"
            .formatted(objective.name().toLowerCase(Locale.ROOT)));
  }
}
