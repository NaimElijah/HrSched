package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.NotATenantMemberException;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.Optional;

/**
 * Driving port: workforce's own reads of the people an organisation employs, contract included.
 *
 * <p>For this context's own adapters, and consumed by no other context. Scheduling reads people
 * through {@link FindRosterableEmployeesUseCase} and access through {@link
 * ManageEmployeesUseCase#findEmployee}, and both answer with {@link RosterableEmployee}, which
 * carries no contracted hours. Each read here is restricted exactly as its published counterpart
 * is, because it carries the same absences and more.
 */
public interface FindEmployeeRecordsUseCase {

  /**
   * Lists everyone, restricted exactly as {@link FindRosterableEmployeesUseCase#findRosterable} is.
   *
   * @param actor whoever is asking; must be an Admin or Scheduler of the organisation
   * @param organisation whose employees to list
   * @return every employee of that organisation, with their contracted hours
   * @throws CrossTenantAccessException if the actor may not see that organisation
   * @throws NotATenantMemberException if the actor belongs to no organisation
   * @throws NotPermittedException if the actor is neither an Admin nor a Scheduler
   */
  List<EmployeeRecord> findEmployeeRecords(Actor actor, OrganisationId organisation);

  /**
   * Reads one person, restricted exactly as {@link ManageEmployeesUseCase#findEmployee} is.
   *
   * @param actor whoever is asking; must be an Admin or Scheduler of the organisation
   * @param organisation the organisation to look in
   * @param employee who to read
   * @return them, with their contracted hours, or empty if that organisation holds nobody by that
   *     identity
   * @throws CrossTenantAccessException if the actor may not touch that organisation
   * @throws NotATenantMemberException if the actor belongs to no organisation
   * @throws NotPermittedException if the actor is neither an Admin nor a Scheduler
   */
  Optional<EmployeeRecord> findEmployeeRecord(
      Actor actor, OrganisationId organisation, EmployeeId employee);
}
