package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.ObjectiveWeights;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Optional;

/**
 * Driven port: where each organisation's objective weights are kept (ADR-010).
 *
 * <p>One set per organisation, replaced whole. Weights are not versioned, because nothing is ever
 * judged against them.
 */
public interface ObjectiveWeightsRepository {

  /**
   * Stores these weights in place of whatever the organisation weighted before.
   *
   * @param weights the weights that apply from now on, every objective absent from them unweighted
   */
  void save(ObjectiveWeights weights);

  /**
   * @param organisation whose weights to read
   * @return its weights, or empty if it has never weighted anything
   */
  Optional<ObjectiveWeights> find(OrganisationId organisation);
}
