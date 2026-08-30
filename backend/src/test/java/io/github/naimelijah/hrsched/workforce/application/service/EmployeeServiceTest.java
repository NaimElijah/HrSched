package io.github.naimelijah.hrsched.workforce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.workforce.application.port.in.AddEmployeeCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.DeclareUnavailabilityCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.GrantQualificationCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.RosterableEmployee;
import io.github.naimelijah.hrsched.workforce.application.port.in.SetContractTermsCommand;
import io.github.naimelijah.hrsched.workforce.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.workforce.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.workforce.application.port.out.EmployeeRepository;
import io.github.naimelijah.hrsched.workforce.application.port.out.QualificationRepository;
import io.github.naimelijah.hrsched.workforce.application.port.out.WorkforceChange;
import io.github.naimelijah.hrsched.workforce.domain.model.Employee;
import io.github.naimelijah.hrsched.workforce.domain.model.Qualification;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownEmployeeException;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownQualificationException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Specification of the employee use cases, driven without Spring.
 *
 * <p>The isolation tests here are the ones whose failure would be a security incident rather than a
 * bug. They assert that an actor of one organisation cannot read, write, or even learn of the
 * existence of another organisation's data.
 */
class EmployeeServiceTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();
  private static final TimeRange MONDAY =
      new TimeRange(Instant.parse("2026-09-07T08:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"));

  private final StubEmployeeRepository employees = new StubEmployeeRepository();
  private final StubQualificationRepository qualifications = new StubQualificationRepository();
  private final RecordingAuditTrail audit = new RecordingAuditTrail();
  private final EmployeeService service = new EmployeeService(employees, qualifications, audit);

  private static TenantActor actor(OrganisationId organisation, Role role) {
    return new TenantActor(UserId.newId(), organisation, role);
  }

  private static TenantActor admin(OrganisationId organisation) {
    return new TenantActor(UserId.newId(), organisation, Role.ADMIN);
  }

  @Test
  void GivenAnAdmin_WhenAddingAnEmployee_ThenTheyBelongToThatOrganisation() {
    EmployeeId id = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));

    assertThat(employees.findById(ACME, id)).isPresent();
  }

  @Test
  void GivenAnAdminOfOneOrganisation_WhenAddingToAnother_ThenItIsRefused() {
    assertThatThrownBy(
            () -> service.addEmployee(admin(ACME), new AddEmployeeCommand(RIVAL, "Mole")))
        .isInstanceOf(CrossTenantAccessException.class);

    assertThat(employees.findByOrganisation(RIVAL)).isEmpty();
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenListingRosterable_ThenNothingLeaks() {
    service.addEmployee(admin(RIVAL), new AddEmployeeCommand(RIVAL, "Their Employee"));
    service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Our Employee"));

    List<RosterableEmployee> visible = service.findRosterable(admin(ACME), ACME);

    assertThat(visible).extracting(RosterableEmployee::name).containsExactly("Our Employee");
  }

  @Test
  void GivenAnActorOfOneOrganisation_WhenListingAnother_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findRosterable(admin(ACME), RIVAL))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenActingOnThemByIdentity_ThenTheyAreUnknown() {
    EmployeeId theirs =
        service.addEmployee(admin(RIVAL), new AddEmployeeCommand(RIVAL, "Their Employee"));

    assertThatThrownBy(
            () ->
                service.declareUnavailability(
                    admin(ACME), new DeclareUnavailabilityCommand(ACME, theirs, MONDAY)))
        .describedAs("knowing an identity from another tenant must not be enough to act on it")
        .isInstanceOf(UnknownEmployeeException.class);
  }

  @Test
  void GivenAnOperator_WhenListingAnyOrganisation_ThenItIsPermitted() {
    service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    Actor operator = new Operator(UserId.newId());

    assertThat(service.findRosterable(operator, ACME)).hasSize(1);
  }

  @Test
  void GivenASchedulerRatherThanAnAdmin_WhenAddingAnEmployee_ThenItIsRefused() {
    TenantActor scheduler = new TenantActor(UserId.newId(), ACME, Role.SCHEDULER);

    assertThatThrownBy(() -> service.addEmployee(scheduler, new AddEmployeeCommand(ACME, "Ada")))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAnEmployee_WhenDeclaringTheirOwnUnavailability_ThenItIsAllowed() {
    EmployeeId id = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    TenantActor themselves = new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE, Optional.of(id));

    assertThatCode(
            () ->
                service.declareUnavailability(
                    themselves, new DeclareUnavailabilityCommand(ACME, id, MONDAY)))
        .doesNotThrowAnyException();
  }

  @Test
  void GivenAnEmployee_WhenDeclaringUnavailabilityForSomeoneElse_ThenItIsRefused() {
    EmployeeId other = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    TenantActor someoneElse = new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE);

    assertThatThrownBy(
            () ->
                service.declareUnavailability(
                    someoneElse, new DeclareUnavailabilityCommand(ACME, other, MONDAY)))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAnEmployeeLinkedToAnotherRecord_WhenDeclaringUnavailability_ThenItIsRefused() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    EmployeeId grace = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Grace"));
    TenantActor gracesAccount =
        new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE, Optional.of(grace));

    assertThatThrownBy(
            () ->
                service.declareUnavailability(
                    gracesAccount, new DeclareUnavailabilityCommand(ACME, ada, MONDAY)))
        .describedAs("holding an employee link must not permit acting on a different record")
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAnEmployee_WhenListingColleagues_ThenItIsRefused() {
    service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    TenantActor employee = new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE);

    assertThatThrownBy(() -> service.findRosterable(employee, ACME))
        .describedAs("unavailability is personal data; an employee has no reason to read another's")
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAQualificationOfAnotherOrganisation_WhenGrantingIt_ThenItIsUnknown() {
    EmployeeId ours = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    QualificationId theirs = QualificationId.newId();
    qualifications.save(new Qualification(theirs, RIVAL, "Their Ticket"));

    assertThatThrownBy(
            () ->
                service.grantQualification(
                    admin(ACME), new GrantQualificationCommand(ACME, ours, theirs)))
        .describedAs("a qualification defined by another tenant must not be grantable here")
        .isInstanceOf(UnknownQualificationException.class);
  }

  @Test
  void GivenAnEmployeeWithUnavailability_WhenPublished_ThenItTravelsWithThem() {
    EmployeeId id = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    service.declareUnavailability(admin(ACME), new DeclareUnavailabilityCommand(ACME, id, MONDAY));

    RosterableEmployee published = service.findRosterable(admin(ACME), ACME).getFirst();

    assertThat(published.isAvailableFor(MONDAY)).isFalse();
  }

  /** In-memory stand-in for the driven port, scoped by organisation exactly as the real one is. */
  private static final class StubEmployeeRepository implements EmployeeRepository {

    private final Map<EmployeeId, Employee> saved = new HashMap<>();

    @Override
    public void save(Employee employee) {
      saved.put(employee.id(), employee);
    }

    @Override
    public Optional<Employee> findById(OrganisationId organisation, EmployeeId id) {
      return Optional.ofNullable(saved.get(id))
          .filter(employee -> employee.organisation().equals(organisation));
    }

    @Override
    public List<Employee> findByOrganisation(OrganisationId organisation) {
      List<Employee> matching = new ArrayList<>(saved.values());
      matching.removeIf(employee -> !employee.organisation().equals(organisation));
      matching.sort(Comparator.comparing(Employee::name));
      return List.copyOf(matching);
    }
  }

  /** In-memory stand-in for the qualification port. */
  private static final class StubQualificationRepository implements QualificationRepository {

    private final Map<QualificationId, Qualification> saved = new HashMap<>();

    @Override
    public void save(Qualification qualification) {
      saved.put(qualification.id(), qualification);
    }

    @Override
    public Optional<Qualification> findById(OrganisationId organisation, QualificationId id) {
      return Optional.ofNullable(saved.get(id))
          .filter(qualification -> qualification.organisation().equals(organisation));
    }

    @Override
    public List<Qualification> findByOrganisation(OrganisationId organisation) {
      return saved.values().stream()
          .filter(qualification -> qualification.organisation().equals(organisation))
          .toList();
    }
  }

  @Test
  void GivenAnAdmin_WhenAddingSomeone_ThenTheChangeIsRecordedAgainstTheActingUser() {
    TenantActor admin = admin(ACME);

    EmployeeId added = service.addEmployee(admin, new AddEmployeeCommand(ACME, "Ada"));

    assertThat(audit.of(WorkforceChange.EMPLOYEE_ADDED))
        .containsExactly(
            new AuditedChange(ACME, admin.id(), WorkforceChange.EMPLOYEE_ADDED, added.value()));
  }

  /**
   * The case the trail exists for. An employee editing their own record and a manager editing it
   * for them are the same change to the same subject, and only the actor tells them apart - which
   * is why {@link UserId} and {@link EmployeeId} are never conflated (ADR-012).
   */
  @Test
  void
      GivenSomeoneDeclaringTheirOwnUnavailability_WhenRecorded_ThenTheActorIsThemNotTheirManager() {
    EmployeeId subject = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    TenantActor themself =
        new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE, Optional.of(subject));

    service.declareUnavailability(
        themself, new DeclareUnavailabilityCommand(ACME, subject, MONDAY));

    assertThat(audit.of(WorkforceChange.UNAVAILABILITY_DECLARED))
        .containsExactly(
            new AuditedChange(
                ACME, themself.id(), WorkforceChange.UNAVAILABILITY_DECLARED, subject.value()));
  }

  @Test
  void GivenAManagerActingOnSomeoneElse_WhenRecorded_ThenTheSubjectIsTheEmployeeNotTheManager() {
    EmployeeId subject = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    TenantActor manager = admin(ACME);

    service.setContractTerms(
        manager, new SetContractTermsCommand(ACME, subject, Duration.ofHours(20)));

    assertThat(audit.of(WorkforceChange.CONTRACT_SET))
        .containsExactly(
            new AuditedChange(ACME, manager.id(), WorkforceChange.CONTRACT_SET, subject.value()));
  }

  @Test
  void GivenAnActorOfAnotherOrganisation_WhenTheyAreRefused_ThenNothingIsRecorded() {
    assertThatThrownBy(() -> service.addEmployee(admin(RIVAL), new AddEmployeeCommand(ACME, "Ada")))
        .isInstanceOf(CrossTenantAccessException.class);

    assertThat(audit.changes()).isEmpty();
  }

  @Test
  void GivenAnEmployeeRole_WhenTheyMayNotAdminister_ThenTheRefusalLeavesNoEntry() {
    assertThatThrownBy(
            () ->
                service.addEmployee(
                    actor(ACME, Role.EMPLOYEE), new AddEmployeeCommand(ACME, "Ada")))
        .isInstanceOf(NotPermittedException.class);

    assertThat(audit.changes()).isEmpty();
  }

  /**
   * Records what the service says was changed.
   *
   * <p>Hand-written, like every other double here: the audit trail is our own port, and asking it
   * what it was told is how "was this recorded?" becomes a test rather than a review comment.
   */
  private static final class RecordingAuditTrail implements AuditTrail {

    private final List<AuditedChange> recorded = new ArrayList<>();

    @Override
    public void record(AuditedChange change) {
      recorded.add(change);
    }

    List<WorkforceChange> changes() {
      return recorded.stream().map(AuditedChange::change).toList();
    }

    List<AuditedChange> of(WorkforceChange change) {
      return recorded.stream().filter(entry -> entry.change() == change).toList();
    }
  }
}
