package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;

/**
 * Driving port: maintaining the people an organisation can roster.
 *
 * <p>Every method takes the {@link Actor} performing it. Authorisation is not something an adapter
 * is trusted to have checked before calling; it is checked here, once, for every caller.
 */
public interface ManageEmployeesUseCase {

  /**
   * @param actor whoever is adding them; must be an Admin of the organisation
   * @param command who to add
   * @return the new employee's identity
   * @throws CrossTenantAccessException if the actor may not touch that organisation
   * @throws NotPermittedException if the actor is not an Admin
   */
  EmployeeId addEmployee(Actor actor, AddEmployeeCommand command);

  /**
   * @param actor whoever is granting it; must be an Admin of the organisation
   * @param command who holds what
   * @throws CrossTenantAccessException if the actor may not touch that organisation
   */
  void grantQualification(Actor actor, GrantQualificationCommand command);

  /**
   * @param actor whoever is setting them; must be an Admin of the organisation
   * @param command whose terms, and what they are
   */
  void setContractTerms(Actor actor, SetContractTermsCommand command);

  /**
   * @param actor the employee themselves, or an Admin or Scheduler of their organisation
   * @param command who is unavailable, and when
   */
  void declareUnavailability(Actor actor, DeclareUnavailabilityCommand command);

  /**
   * @param actor the employee themselves, or an Admin or Scheduler of their organisation
   * @param command whose preference, and what it is
   */
  void statePreference(Actor actor, StatePreferenceCommand command);
}
