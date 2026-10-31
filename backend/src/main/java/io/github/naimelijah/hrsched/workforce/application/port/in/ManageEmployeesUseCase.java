package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.workforce.domain.model.ShiftPreference;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownDeclarationException;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownEmployeeException;
import java.util.List;
import java.util.Optional;

/**
 * Driving port: maintaining the people an organisation can roster.
 *
 * <p>Every method takes the {@link Actor} performing it. Authorisation is not something an adapter
 * is trusted to have checked before calling; every implementation checks it, once, for every
 * caller. The platform {@code Operator} belongs to no organisation, so every method refuses it with
 * a {@code NotATenantMemberException}.
 *
 * <p>A method acting on or reading one employee's record refuses with an {@link
 * UnknownEmployeeException} when the organisation holds nobody by that identity, and does not tell
 * an employee of another organisation apart from none. {@link #findEmployee} answers empty instead.
 */
public interface ManageEmployeesUseCase {

  /**
   * Adds a person to the organisation's records, holding no qualification and with no absence,
   * preference or contract.
   *
   * <p>A blank name is refused with an {@code InvalidEmployeeNameException}.
   *
   * @param actor whoever is adding them; must be an Admin of the organisation
   * @param command who to add
   * @return the new employee's identity
   * @throws CrossTenantAccessException if the actor may not touch that organisation
   * @throws NotPermittedException if the actor is not an Admin
   */
  EmployeeId addEmployee(Actor actor, AddEmployeeCommand command);

  /**
   * Records that an employee holds a qualification the organisation defined; holding it already is
   * not an error.
   *
   * <p>A qualification the organisation does not define, which includes another organisation's, is
   * refused with an {@code UnknownQualificationException}.
   *
   * @param actor whoever is granting it; must be an Admin of the organisation
   * @param command who holds what
   * @throws CrossTenantAccessException if the actor may not touch that organisation
   * @throws NotPermittedException if the actor is not an Admin
   * @throws UnknownEmployeeException if that organisation holds nobody by that identity
   */
  void grantQualification(Actor actor, GrantQualificationCommand command);

  /**
   * Sets what an employee is contracted to work, replacing any terms they had.
   *
   * <p>Hours that are not positive are refused with an {@code InvalidContractTermsException}. Hours
   * with a part smaller than a second are refused by the store with an {@link
   * IllegalArgumentException}, as it holds whole seconds.
   *
   * @param actor whoever is setting them; must be an Admin of the organisation
   * @param command whose terms, and what they are
   * @throws CrossTenantAccessException if the actor may not touch that organisation
   * @throws NotPermittedException if the actor is not an Admin
   * @throws UnknownEmployeeException if that organisation holds nobody by that identity
   */
  void setContractTerms(Actor actor, SetContractTermsCommand command);

  /**
   * Declares a span an employee cannot work (F6).
   *
   * <p>A span overlapping one already declared is refused with an {@code
   * OverlappingUnavailabilityException}; a span that only abuts one is accepted.
   *
   * @param actor the employee themselves, or an Admin or Scheduler of their organisation
   * @param command who is unavailable, and when
   * @throws CrossTenantAccessException if the actor may not touch that organisation
   * @throws NotPermittedException if the actor is somebody else without an Admin or Scheduler role
   * @throws UnknownEmployeeException if that organisation holds nobody by that identity
   */
  void declareUnavailability(Actor actor, DeclareUnavailabilityCommand command);

  /**
   * States a soft preference about when an employee would rather work (F6). It blocks nothing, so
   * it is never refused for overlapping anything, a repeat of itself included.
   *
   * @param actor the employee themselves, or an Admin or Scheduler of their organisation
   * @param command whose preference, and what it is
   * @throws CrossTenantAccessException if the actor may not touch that organisation
   * @throws NotPermittedException if the actor is somebody else without an Admin or Scheduler role
   * @throws UnknownEmployeeException if that organisation holds nobody by that identity
   */
  void statePreference(Actor actor, StatePreferenceCommand command);

  /**
   * Withdraws a span somebody declared they cannot work (F26), named exactly as it was declared
   * (ADR-040). It only ever widens who may be assigned, so no roster is touched.
   *
   * @param actor the employee themselves, or an Admin or Scheduler of their organisation
   * @param command who declared it, and the span exactly as declared
   * @throws CrossTenantAccessException if the actor may not touch that organisation
   * @throws NotPermittedException if the actor is somebody else without an Admin or Scheduler role
   * @throws UnknownEmployeeException if that organisation holds nobody by that identity
   * @throws UnknownDeclarationException if they declared no span exactly like it
   */
  void withdrawUnavailability(Actor actor, WithdrawUnavailabilityCommand command);

  /**
   * Withdraws a soft preference somebody stated (F26), named by its span and strength (ADR-040).
   * Every identical statement goes with it, because identical statements cannot be told apart.
   *
   * @param actor the employee themselves, or an Admin or Scheduler of their organisation
   * @param command who stated it, and the span and strength exactly as stated
   * @throws CrossTenantAccessException if the actor may not touch that organisation
   * @throws NotPermittedException if the actor is somebody else without an Admin or Scheduler role
   * @throws UnknownEmployeeException if that organisation holds nobody by that identity
   * @throws UnknownDeclarationException if they stated no preference exactly like it
   */
  void withdrawPreference(Actor actor, WithdrawPreferenceCommand command);

  /**
   * Reads one person, restricted exactly as {@link FindRosterableEmployeesUseCase#findRosterable}
   * is, because the answer carries the same absences.
   *
   * <p>Declared here rather than on {@code FindRosterableEmployeesUseCase} because that port is
   * what scheduling consumes (ADR-008), and nothing there needs one person at a time.
   *
   * @param actor whoever is asking; must be an Admin or Scheduler of the organisation
   * @param organisation the organisation to look in
   * @param employee who to read
   * @return them, or empty if that organisation holds nobody by that identity
   * @throws CrossTenantAccessException if the actor may not touch that organisation
   * @throws NotPermittedException if the actor is neither an Admin nor a Scheduler
   */
  Optional<RosterableEmployee> findEmployee(
      Actor actor, OrganisationId organisation, EmployeeId employee);

  /**
   * Reads back the spans somebody declared they cannot work (F6).
   *
   * <p>Authorised as declaring is, not as listing: the person themself, or an Admin or Scheduler on
   * their behalf. That is no widening of R12, because it answers for one person only, and the
   * person reading it is either them or somebody who may already read everyone's.
   *
   * @param actor the employee themselves, or an Admin or Scheduler of their organisation
   * @param organisation the organisation to look in
   * @param employee whose declarations to read
   * @return their declared unavailability, in the order it was declared
   * @throws CrossTenantAccessException if the actor may not touch that organisation
   * @throws NotPermittedException if the actor is somebody else without an Admin or Scheduler role
   * @throws UnknownEmployeeException if that organisation holds nobody by that identity
   */
  List<TimeRange> findUnavailability(Actor actor, OrganisationId organisation, EmployeeId employee);

  /**
   * Reads back the soft preferences somebody stated (F6), authorised as {@link #findUnavailability}
   * is.
   *
   * @param actor the employee themselves, or an Admin or Scheduler of their organisation
   * @param organisation the organisation to look in
   * @param employee whose preferences to read
   * @return their stated preferences, in the order they were stated
   * @throws CrossTenantAccessException if the actor may not touch that organisation
   * @throws NotPermittedException if the actor is somebody else without an Admin or Scheduler role
   * @throws UnknownEmployeeException if that organisation holds nobody by that identity
   */
  List<ShiftPreference> findPreferences(
      Actor actor, OrganisationId organisation, EmployeeId employee);
}
