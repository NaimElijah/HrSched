package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Hand-written, like every other double here: the repository is our own port. */
final class FakeRosters implements RosterRepository {

  private final Map<RosterId, Roster> stored = new LinkedHashMap<>();

  @Override
  public void save(Roster roster) {
    stored.put(roster.id(), roster);
  }

  @Override
  public Optional<Roster> findById(OrganisationId organisation, RosterId id) {
    return Optional.ofNullable(stored.get(id))
        .filter(roster -> roster.organisation().equals(organisation));
  }

  @Override
  public List<Roster> findByOrganisation(OrganisationId organisation) {
    return stored.values().stream()
        .filter(roster -> roster.organisation().equals(organisation))
        .toList();
  }

  /** Reads without a tenant, so a test can check what was stored rather than what is visible. */
  Roster byId(RosterId id) {
    return stored.get(id);
  }
}
