package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.ObjectiveWeightsRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.ObjectiveWeights;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Hand-written, like every other double here: the repository is our own port.
 *
 * <p>One set per organisation, each save replacing the last, as {@code
 * JdbcObjectiveWeightsRepository} keeps one row. A weight the schema would refuse cannot reach it:
 * {@link ObjectiveWeights} refuses the same weights first.
 */
final class FakeObjectiveWeights implements ObjectiveWeightsRepository {

  private final Map<OrganisationId, ObjectiveWeights> stored = new HashMap<>();

  @Override
  public void save(ObjectiveWeights weights) {
    stored.put(weights.organisation(), weights);
  }

  @Override
  public Optional<ObjectiveWeights> find(OrganisationId organisation) {
    return Optional.ofNullable(stored.get(organisation));
  }
}
