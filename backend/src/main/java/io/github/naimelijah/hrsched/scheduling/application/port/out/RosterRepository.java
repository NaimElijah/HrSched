package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.Optional;

/** Driven port: storing rosters, scoped by organisation. */
public interface RosterRepository {

  void save(Roster roster);

  Optional<Roster> findById(OrganisationId organisation, RosterId id);

  List<Roster> findByOrganisation(OrganisationId organisation);
}
