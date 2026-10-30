package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;

/**
 * Workforce's published API: who an organisation could roster.
 *
 * <p>One of three of this package's ports another context uses, each from an {@code
 * adapter.out.workforce} class: scheduling reads who it could roster through this one and
 * qualifications through {@code ManageQualificationsUseCase}, and access asks {@code
 * ManageEmployeesUseCase.findEmployee} whether an employee exists. Another context may reach this
 * package and nothing else (ADR-008): this context's aggregate, its services and its storage stay
 * private.
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
