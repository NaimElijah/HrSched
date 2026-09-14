package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Hand-written, like every other double here: the repository is our own port. */
final class FakeRosters implements RosterRepository {

  /** What a use case asked of the repository, apart from saving. */
  enum Call {
    LOCK_PUBLICATIONS,
    FIND_BY_ID,
    FIND_BY_ORGANISATION
  }

  private final Map<RosterId, Roster> stored = new LinkedHashMap<>();
  private final List<Call> calls = new ArrayList<>();

  @Override
  public void save(Roster roster) {
    stored.put(roster.id(), roster);
  }

  /**
   * Holds nothing: these tests have one thread and one store. Recorded instead, because the order a
   * use case takes it in against its reads is the part a use case can get wrong. That the lock
   * really makes a second publication wait is proven against PostgreSQL, in {@code
   * JdbcRosterRepositoryTest}.
   */
  @Override
  public void lockPublicationsOf(OrganisationId organisation) {
    calls.add(Call.LOCK_PUBLICATIONS);
  }

  @Override
  public Optional<Roster> findById(OrganisationId organisation, RosterId id) {
    calls.add(Call.FIND_BY_ID);
    return Optional.ofNullable(stored.get(id))
        .filter(roster -> roster.organisation().equals(organisation));
  }

  @Override
  public List<Roster> findByOrganisation(OrganisationId organisation) {
    calls.add(Call.FIND_BY_ORGANISATION);
    return stored.values().stream()
        .filter(roster -> roster.organisation().equals(organisation))
        .toList();
  }

  /** Reads without a tenant, so a test can check what was stored rather than what is visible. */
  Roster byId(RosterId id) {
    return stored.get(id);
  }

  /**
   * @return every lock and read since the last {@link #forgetCalls()}, in the order they were made
   */
  List<Call> calls() {
    return List.copyOf(calls);
  }

  /** Starts the record afresh, so a test sees only what the call under test asked for. */
  void forgetCalls() {
    calls.clear();
  }
}
