package io.github.naimelijah.hrsched.workforce.application.service;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.workforce.application.port.in.AddEmployeeCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.DeclareUnavailabilityCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.FindRosterableEmployeesUseCase;
import io.github.naimelijah.hrsched.workforce.application.port.in.GrantQualificationCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.ManageEmployeesUseCase;
import io.github.naimelijah.hrsched.workforce.application.port.in.RosterableEmployee;
import io.github.naimelijah.hrsched.workforce.application.port.in.SetContractTermsCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.StatePreferenceCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.WithdrawPreferenceCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.WithdrawUnavailabilityCommand;
import io.github.naimelijah.hrsched.workforce.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.workforce.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.workforce.application.port.out.EmployeeRepository;
import io.github.naimelijah.hrsched.workforce.application.port.out.QualificationRepository;
import io.github.naimelijah.hrsched.workforce.application.port.out.WorkforceChange;
import io.github.naimelijah.hrsched.workforce.domain.model.ContractTerms;
import io.github.naimelijah.hrsched.workforce.domain.model.Employee;
import io.github.naimelijah.hrsched.workforce.domain.model.PreferenceStrength;
import io.github.naimelijah.hrsched.workforce.domain.model.ShiftPreference;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownEmployeeException;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownQualificationException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
  private static final Set<Role> MAY_ACT_FOR_ANYONE = Set.of(Role.ADMIN, Role.SCHEDULER);

  private final EmployeeRepository employees;
  private final QualificationRepository qualifications;
  private final AuditTrail audit;

  /**
   * @throws NullPointerException if any argument is {@code null}
   */
  public EmployeeService(
      EmployeeRepository employees, QualificationRepository qualifications, AuditTrail audit) {
    this.employees = Objects.requireNonNull(employees, "employees must not be null");
    this.qualifications = Objects.requireNonNull(qualifications, "qualifications must not be null");
    this.audit = Objects.requireNonNull(audit, "audit must not be null");
  }

  @Override
  public EmployeeId addEmployee(Actor actor, AddEmployeeCommand command) {
    authorise(actor, command.organisation(), MAY_ADMINISTER);
    Employee employee = new Employee(EmployeeId.newId(), command.organisation(), command.name());
    employees.save(employee);
    recorded(actor, command.organisation(), WorkforceChange.EMPLOYEE_ADDED, employee.id());
    return employee.id();
  }

  @Override
  public void grantQualification(Actor actor, GrantQualificationCommand command) {
    authorise(actor, command.organisation(), MAY_ADMINISTER);
    qualifications
        .findById(command.organisation(), command.qualification())
        .orElseThrow(() -> new UnknownQualificationException(command.qualification()));
    Employee employee = loadForUpdate(command.organisation(), command.employee());
    employees.save(employee.holding(command.qualification()));
    recorded(
        actor, command.organisation(), WorkforceChange.QUALIFICATION_GRANTED, command.employee());
  }

  @Override
  public void setContractTerms(Actor actor, SetContractTermsCommand command) {
    authorise(actor, command.organisation(), MAY_ADMINISTER);
    Employee employee = loadForUpdate(command.organisation(), command.employee());
    employees.save(employee.contractedTo(new ContractTerms(command.weeklyContractedHours())));
    recorded(actor, command.organisation(), WorkforceChange.CONTRACT_SET, command.employee());
  }

  @Override
  public void declareUnavailability(Actor actor, DeclareUnavailabilityCommand command) {
    authoriseSelfOrManager(actor, command.organisation(), command.employee());
    Employee employee = loadForUpdate(command.organisation(), command.employee());
    employees.save(employee.unavailableFor(command.period()));
    recorded(
        actor, command.organisation(), WorkforceChange.UNAVAILABILITY_DECLARED, command.employee());
  }

  @Override
  public void statePreference(Actor actor, StatePreferenceCommand command) {
    authoriseSelfOrManager(actor, command.organisation(), command.employee());
    Employee employee = loadForUpdate(command.organisation(), command.employee());
    employees.save(employee.preferring(new ShiftPreference(command.period(), command.strength())));
    recorded(actor, command.organisation(), WorkforceChange.PREFERENCE_STATED, command.employee());
  }

  @Override
  public void withdrawUnavailability(Actor actor, WithdrawUnavailabilityCommand command) {
    authoriseSelfOrManager(actor, command.organisation(), command.employee());
    Employee employee = loadForUpdate(command.organisation(), command.employee());
    employees.save(employee.noLongerUnavailableFor(command.period()));
    recorded(
        actor,
        command.organisation(),
        WorkforceChange.UNAVAILABILITY_WITHDRAWN,
        command.employee());
  }

  @Override
  public void withdrawPreference(Actor actor, WithdrawPreferenceCommand command) {
    authoriseSelfOrManager(actor, command.organisation(), command.employee());
    Employee employee = loadForUpdate(command.organisation(), command.employee());
    employees.save(
        employee.noLongerPreferring(new ShiftPreference(command.period(), command.strength())));
    recorded(
        actor, command.organisation(), WorkforceChange.PREFERENCE_WITHDRAWN, command.employee());
  }

  /**
   * Restricted to Admins and Schedulers: unavailability is personal data — medical appointments,
   * caring responsibilities — and an employee has no reason to read a colleague's.
   */
  @Override
  public List<RosterableEmployee> findRosterable(Actor actor, OrganisationId organisation) {
    authorise(actor, organisation, MAY_ACT_FOR_ANYONE);
    return employees.findByOrganisation(organisation).stream()
        .map(EmployeeService::publish)
        .toList();
  }

  @Override
  public Optional<RosterableEmployee> findEmployee(
      Actor actor, OrganisationId organisation, EmployeeId employee) {
    authorise(actor, organisation, MAY_ACT_FOR_ANYONE);
    return employees.findById(organisation, employee).map(EmployeeService::publish);
  }

  @Override
  public List<TimeRange> findUnavailability(
      Actor actor, OrganisationId organisation, EmployeeId employee) {
    authoriseSelfOrManager(actor, organisation, employee);
    return load(organisation, employee).unavailability();
  }

  @Override
  public List<ShiftPreference> findPreferences(
      Actor actor, OrganisationId organisation, EmployeeId employee) {
    authoriseSelfOrManager(actor, organisation, employee);
    return load(organisation, employee).preferences();
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
        employee.contractTerms().map(ContractTerms::weeklyContractedHours),
        spansStated(employee, PreferenceStrength.PREFERS),
        spansStated(employee, PreferenceStrength.PREFERS_NOT));
  }

  private static List<TimeRange> spansStated(Employee employee, PreferenceStrength strength) {
    return employee.preferences().stream()
        .filter(preference -> preference.strength() == strength)
        .map(ShiftPreference::period)
        .toList();
  }

  /**
   * Held from this read until the change commits, because the change stores the employee whole: a
   * second change to the same person waits, then reads this one's rather than storing over it (#33,
   * ADR-041). Reads use {@link #load}, which holds nothing.
   */
  private Employee loadForUpdate(OrganisationId organisation, EmployeeId id) {
    return employees
        .findForUpdate(organisation, id)
        .orElseThrow(() -> new UnknownEmployeeException(id));
  }

  private Employee load(OrganisationId organisation, EmployeeId id) {
    return employees.findById(organisation, id).orElseThrow(() -> new UnknownEmployeeException(id));
  }

  /**
   * Records a change that has already been saved.
   *
   * <p>Called after the repository, never before: an entry for something that then failed to store
   * is a record of something that did not happen.
   *
   * <p>The actor is the acting user, not the employee whose record changed. Those are different
   * identities on purpose (ADR-012), and conflating them here would lose the case the trail exists
   * for — somebody editing a record that is not their own.
   */
  private void recorded(
      Actor actor, OrganisationId organisation, WorkforceChange change, EmployeeId subject) {
    audit.record(new AuditedChange(organisation, actor.id(), change, subject.value()));
  }

  private void authorise(Actor actor, OrganisationId organisation, Set<Role> permitted) {
    actor.requireAccessTo(organisation).requireRoleIn(permitted);
  }

  /**
   * Anyone may act on their own record; acting on someone else's takes an Admin or Scheduler.
   *
   * <p>Whether the actor <em>is</em> the subject is decided by the employee link the actor carries,
   * never by comparing a {@link io.github.naimelijah.hrsched.shared.domain.model.UserId} with an
   * {@link EmployeeId}. Those are deliberately different identities and comparing them is always
   * false, which silently makes self-service impossible.
   */
  private void authoriseSelfOrManager(
      Actor actor, OrganisationId organisation, EmployeeId subject) {
    TenantActor tenant = actor.requireAccessTo(organisation);
    if (!tenant.isThemself(subject)) {
      tenant.requireRoleIn(MAY_ACT_FOR_ANYONE);
    }
  }
}
