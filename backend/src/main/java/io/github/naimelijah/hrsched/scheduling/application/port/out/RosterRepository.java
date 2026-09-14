package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterPublicationConflictException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.Optional;

/** Driven port: storing rosters, scoped by organisation. */
public interface RosterRepository {

  /**
   * Stores a roster, replacing whatever was stored under its identity.
   *
   * @param roster the roster as it now is
   * @throws RosterPublicationConflictException if it is published and overlaps another published
   *     roster of its organisation. Publication supersedes before it publishes, so this means
   *     another publication was in flight at the same time (ADR-026)
   */
  void save(Roster roster);

  Optional<Roster> findById(OrganisationId organisation, RosterId id);

  List<Roster> findByOrganisation(OrganisationId organisation);
}
