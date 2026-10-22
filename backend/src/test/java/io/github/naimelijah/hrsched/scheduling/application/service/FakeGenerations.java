package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerationRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationId;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterGeneration;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hand-written, like every other double here: the store is our own port.
 *
 * <p>Refuses what {@code JdbcRosterGenerationRepository} refuses: ending a generation that has
 * already ended, or saving over one another organisation holds.
 */
final class FakeGenerations implements RosterGenerationRepository {

  private final Map<GenerationId, RosterGeneration> stored = new LinkedHashMap<>();

  @Override
  public void save(RosterGeneration generation) {
    RosterGeneration before = stored.get(generation.id());
    if (before != null
        && (!before.organisation().equals(generation.organisation())
            || before.status() != GenerationStatus.PENDING)) {
      throw new IllegalStateException(
          "Generation %s has already ended, or belongs to another organisation"
              .formatted(generation.id().value()));
    }
    stored.put(generation.id(), generation);
  }

  @Override
  public Optional<RosterGeneration> findById(OrganisationId organisation, GenerationId id) {
    return Optional.ofNullable(stored.get(id))
        .filter(generation -> generation.organisation().equals(organisation));
  }

  /** Reads without a tenant, so a test can check what was stored rather than what is visible. */
  List<RosterGeneration> all() {
    return List.copyOf(stored.values());
  }
}
