package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;

/**
 * Workforce's published API: who an organisation could roster.
 *
 * <p>The one entry point other contexts are permitted to use. Everything else about this context —
 * its aggregate, its services, its storage — stays private.
 */
public interface FindRosterableEmployeesUseCase {

  /**
   * @param actor whoever is asking
   * @param organisation whose employees to list
   * @return every employee of that organisation, as published data
   * @throws CrossTenantAccessException if the actor may not see that organisation
   */
  List<RosterableEmployee> findRosterable(Actor actor, OrganisationId organisation);
}
