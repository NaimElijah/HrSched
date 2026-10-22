package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterGeneration;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Optional;

/** Driven port: where each generation is kept, so that any instance can say how it stands. */
public interface RosterGenerationRepository {

  /**
   * Stores a generation just asked for, or how it ended.
   *
   * @throws IllegalStateException if it would change a generation another organisation holds
   */
  void save(RosterGeneration generation);

  /**
   * @return the generation, if this organisation holds one with that identity
   */
  Optional<RosterGeneration> findById(OrganisationId organisation, GenerationId id);
}
