package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;

/**
 * Driving port: who an organisation could roster, as scheduling reads them.
 *
 * <p>Scheduling consumes it, beside {@link ManageQualificationsUseCase}; access consumes {@link
 * ManageEmployeesUseCase}. Each answers in the published language, {@link RosterableEmployee},
 * never the aggregate. Workforce's own screens read people through {@link
 * FindEmployeeRecordsUseCase} instead. This context's aggregate, services and storage stay private.
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
