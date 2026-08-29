package io.github.naimelijah.hrsched.workforce.application.service;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.workforce.application.port.in.AddEmployeeCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.DeclareUnavailabilityCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.FindRosterableEmployeesUseCase;
import io.github.naimelijah.hrsched.workforce.application.port.in.GrantQualificationCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.ManageEmployeesUseCase;
import io.github.naimelijah.hrsched.workforce.application.port.in.RosterableEmployee;
import io.github.naimelijah.hrsched.workforce.application.port.in.SetContractTermsCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.StatePreferenceCommand;
import io.github.naimelijah.hrsched.workforce.application.port.out.EmployeeRepository;
import io.github.naimelijah.hrsched.workforce.application.port.out.QualificationRepository;
import io.github.naimelijah.hrsched.workforce.domain.model.ContractTerms;
import io.github.naimelijah.hrsched.workforce.domain.model.Employee;
import io.github.naimelijah.hrsched.workforce.domain.model.ShiftPreference;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownEmployeeException;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownQualificationException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Orchestrates the employee use cases.
 *
 * <p>Holds no domain rules: it authorises, loads, delegates to the aggregate, and stores. Carries
 * no Spring annotation, so it can be constructed with {@code new} in a test.
 *
 * <p>Every method begins by proving the actor may touch the organisation named in the command. That
 * check is here rather than in an adapter because there is more than one adapter, and a guarantee
 * that depends on every caller remembering is not a guarantee.
 */
public class EmployeeService implements ManageEmployeesUseCase, FindRosterableEmployeesUseCase {

  private static final Set<Role> MAY_ADMINISTER = Set.of(Role.ADMIN);
  private static final Set<Role> MAY_SEE_EVERYONE = Set.of(Role.ADMIN, Role.SCHEDULER);

  private final EmployeeRepository employees;
  private final QualificationRepository qualifications;

  public EmployeeService(EmployeeRepository employees, QualificationRepository qualifications) {
    this.employees = Objects.requireNonNull(employees, "employees must not be null");
    this.qualifications = Objects.requireNonNull(qualifications, "qualifications must not be null");
  }

  @Override
  public EmployeeId addEmployee(Actor actor, AddEmployeeCommand command) {
    authorise(actor, command.organisation(), MAY_ADMINISTER);
    Employee employee = new Employee(EmployeeId.newId(), command.organisation(), command.name());
    employees.save(employee);
    return employee.id();
  }

  @Override
  public void grantQualification(Actor actor, GrantQualificationCommand command) {
    authorise(actor, command.organisation(), MAY_ADMINISTER);
    qualifications
        .findById(command.organisation(), command.qualification())
        .orElseThrow(() -> new UnknownQualificationException(command.qualification()));
    Employee employee = load(command.organisation(), command.employee());
    employees.save(employee.holding(command.qualification()));
  }

  @Override
  public void setContractTerms(Actor actor, SetContractTermsCommand command) {
    authorise(actor, command.organisation(), MAY_ADMINISTER);
    Employee employee = load(command.organisation(), command.employee());
    employees.save(employee.contractedTo(new ContractTerms(command.weeklyContractedHours())));
  }

  @Override
  public void declareUnavailability(Actor actor, DeclareUnavailabilityCommand command) {
    authoriseSelfOrManager(actor, command.organisation(), command.employee());
    Employee employee = load(command.organisation(), command.employee());
    employees.save(employee.unavailableFor(command.period()));
  }

  @Override
  public void statePreference(Actor actor, StatePreferenceCommand command) {
    authoriseSelfOrManager(actor, command.organisation(), command.employee());
    Employee employee = load(command.organisation(), command.employee());
    employees.save(employee.preferring(new ShiftPreference(command.period(), command.strength())));
  }

  @Override
  public List<RosterableEmployee> findRosterable(Actor actor, OrganisationId organisation) {
    actor.requireAccessTo(organisation);
    return employees.findByOrganisation(organisation).stream()
        .map(EmployeeService::publish)
        .toList();
  }

  /**
   * Translates the aggregate into the published language, so that no consumer of this context ever
   * holds one of its entities.
   */
  private static RosterableEmployee publish(Employee employee) {
    return new RosterableEmployee(
        employee.id(),
        employee.name(),
        employee.qualifications(),
        employee.unavailability(),
        employee.contractTerms().map(ContractTerms::weeklyContractedHours));
  }

  private Employee load(OrganisationId organisation, EmployeeId id) {
    return employees.findById(organisation, id).orElseThrow(() -> new UnknownEmployeeException(id));
  }

  private void authorise(Actor actor, OrganisationId organisation, Set<Role> permitted) {
    actor.requireAccessTo(organisation);
    if (actor instanceof TenantActor tenant) {
      tenant.requireRoleIn(permitted);
    }
  }

  /**
   * Employees may declare their own unavailability and preferences; an Admin or Scheduler may do it
   * on anyone's behalf. An Employee acting on someone else is refused.
   */
  private void authoriseSelfOrManager(
      Actor actor, OrganisationId organisation, EmployeeId subject) {
    actor.requireAccessTo(organisation);
    if (actor instanceof TenantActor tenant && tenant.role() == Role.EMPLOYEE) {
      if (!tenant.id().value().equals(subject.value())) {
        tenant.requireRoleIn(MAY_SEE_EVERYONE);
      }
    }
  }
}
