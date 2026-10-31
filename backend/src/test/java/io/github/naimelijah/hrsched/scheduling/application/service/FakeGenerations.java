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
 * <p>Refuses what {@code JdbcRosterGenerationRepository} itself refuses: ending a generation that
 * has already ended, or saving over one another organisation holds.
 *
 * <p>The schema's refusals are not mirrored, and {@code JdbcRosterGenerationRepositoryTest} proves
 * each. This store sees no roster, so it stores a generation naming a draft of another organisation
 * (V14), which {@code
 * GivenADraftOfAnotherOrganisation_WhenAGenerationEndsNamingIt_ThenTheSchemaRefusesIt} proves
 * refused; {@code SchedulingService} names only the draft it has just saved. An ending outside its
 * window (V14) is refused by {@code RosterGeneration} before any store sees it, and by the schema
 * in {@code GivenAnEndingAfterItWasLost_WhenStoredDirectly_ThenTheSchemaRefusesIt} and {@code
 * GivenAnEndingBeforeItWasAskedFor_WhenStoredDirectly_ThenTheSchemaRefusesIt}.
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
