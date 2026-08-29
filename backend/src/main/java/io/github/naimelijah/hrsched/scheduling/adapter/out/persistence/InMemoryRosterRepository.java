package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** Driven adapter storing rosters in memory. PLACEHOLDER until ADR-004. */
@Repository
public class InMemoryRosterRepository implements RosterRepository {

  private final Map<RosterId, Roster> byId = new ConcurrentHashMap<>();

  @Override
  public void save(Roster roster) {
    byId.put(roster.id(), roster);
  }

  @Override
  public Optional<Roster> findById(OrganisationId organisation, RosterId id) {
    return Optional.ofNullable(byId.get(id))
        .filter(roster -> roster.organisation().equals(organisation));
  }

  @Override
  public List<Roster> findByOrganisation(OrganisationId organisation) {
    return byId.values().stream()
        .filter(roster -> roster.organisation().equals(organisation))
        .toList();
  }
}
