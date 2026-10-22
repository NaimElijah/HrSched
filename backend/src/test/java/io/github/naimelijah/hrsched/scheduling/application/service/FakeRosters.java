package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Hand-written, like every other double here: the repository is our own port. */
final class FakeRosters implements RosterRepository {

  private final Map<RosterId, Roster> stored = new LinkedHashMap<>();
  private final CallLog log;
  private Runnable onSave = () -> {};

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

  /**
   * Refuses the writes {@code JdbcRosterRepository} refuses because history is append-only: nothing
   * is stored over a published roster except its supersession, and only a published roster can be
   * superseded. A fake that accepted those would let a use case pass here and fail in production.
   * The schema's other refusals, of an identity another organisation holds and of two rosters
   * governing one moment, are not mirrored; {@code JdbcRosterRepositoryTest} proves those.
   */
  @Override
  public void save(Roster roster) {
    RosterStatus before = stored.containsKey(roster.id()) ? stored.get(roster.id()).status() : null;
    if (roster.status() == RosterStatus.SUPERSEDED && before == null) {
      throw new IllegalStateException("No roster %s is stored to supersede".formatted(roster.id()));
    }
    if (roster.status() == RosterStatus.SUPERSEDED && before != RosterStatus.PUBLISHED) {
      throw new RosterNotEditableException(
          roster.id(), before, "only a published roster can be superseded");
    }
    if (roster.status() != RosterStatus.SUPERSEDED
        && before != null
        && before != RosterStatus.DRAFT) {
      throw new RosterNotEditableException(
          roster.id(), before, "a roster can only be changed while a draft");
    }
    stored.put(roster.id(), roster);
    onSave.run();
  }

  /**
   * Runs {@code observer} after every save, so a test can see where a use case stored something:
   * inside which unit of work, for one.
   */
  void observeSaves(Runnable observer) {
    onSave = observer;
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

  /**
   * Holds nothing, for the reason {@link #lockPublicationsOf} holds nothing. Recorded instead, so a
   * test can see a use case read for update the roster it is about to change. That the read really
   * makes a second edit wait is proven against PostgreSQL, in {@code JdbcRosterRepositoryTest}.
   */
  @Override
  public Optional<Roster> findForUpdate(OrganisationId organisation, RosterId id) {
    log.add(CallLog.Call.FIND_ROSTER_FOR_UPDATE);
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
