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
  private final CallLog log;

  /** A store recording into a log of its own. */
  FakeRosters() {
    this(new CallLog());
  }

  /**
   * @param log where locks and reads are recorded, shared with any other fake whose calls have to
   *     be ordered against these
   */
  FakeRosters(CallLog log) {
    this.log = log;
  }

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
    log.add(CallLog.Call.LOCK_PUBLICATIONS);
  }

  @Override
  public Optional<Roster> findById(OrganisationId organisation, RosterId id) {
    log.add(CallLog.Call.FIND_ROSTER);
    return Optional.ofNullable(stored.get(id))
        .filter(roster -> roster.organisation().equals(organisation));
  }

  @Override
  public List<Roster> findByOrganisation(OrganisationId organisation) {
    log.add(CallLog.Call.FIND_ROSTERS);
    return stored.values().stream()
        .filter(roster -> roster.organisation().equals(organisation))
        .toList();
  }

  /** Reads without a tenant, so a test can check what was stored rather than what is visible. */
  Roster byId(RosterId id) {
    return stored.get(id);
  }

  /**
   * @return the log this store records its locks and reads into
   */
  CallLog log() {
    return log;
  }
}
