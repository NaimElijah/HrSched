package io.github.naimelijah.hrsched.workforce.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.NotATenantMemberException;
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
import io.github.naimelijah.hrsched.workforce.application.port.in.StatePreferenceCommand;
import io.github.naimelijah.hrsched.workforce.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.workforce.application.port.out.EmployeeRepository;
import io.github.naimelijah.hrsched.workforce.application.port.out.QualificationRepository;
import io.github.naimelijah.hrsched.workforce.application.port.out.WorkforceChange;
import io.github.naimelijah.hrsched.workforce.domain.model.Employee;
import io.github.naimelijah.hrsched.workforce.domain.model.PreferenceStrength;
import io.github.naimelijah.hrsched.workforce.domain.model.Qualification;
import io.github.naimelijah.hrsched.workforce.domain.model.ShiftPreference;
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
  private static final TimeRange TUESDAY =
      new TimeRange(Instant.parse("2026-09-08T08:00:00Z"), Instant.parse("2026-09-08T16:00:00Z"));

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

  /** Flipped by ADR-030: a staff list is a tenant's data, and the operator is not a member. */
  @Test
  void GivenTheOperator_WhenListingRosterableEmployees_ThenItIsRefused() {
    service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    Actor operator = new Operator(UserId.newId());

    assertThatThrownBy(() -> service.findRosterable(operator, ACME))
        .isInstanceOf(NotATenantMemberException.class);
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

  @Test
  void GivenAScheduler_WhenReadingSomebodyByIdentity_ThenTheyArePublished() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));

    assertThat(service.findEmployee(actor(ACME, Role.SCHEDULER), ACME, ada))
        .map(RosterableEmployee::name)
        .contains("Ada");
  }

  @Test
  void GivenAnEmployeeRole_WhenReadingSomebodyByIdentity_ThenItIsRefused() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));

    assertThatThrownBy(() -> service.findEmployee(actor(ACME, Role.EMPLOYEE), ACME, ada))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenReadingSomebodyOfOurs_ThenItIsRefused() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));

    assertThatThrownBy(() -> service.findEmployee(admin(RIVAL), ACME, ada))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenAnotherOrganisationsEmployee_WhenReadThroughOurs_ThenNothingIsFound() {
    EmployeeId mole = service.addEmployee(admin(RIVAL), new AddEmployeeCommand(RIVAL, "Mole"));

    assertThat(service.findEmployee(admin(ACME), ACME, mole)).isEmpty();
  }

  // --- F6: reading back what somebody declared ----------------------------------------------

  @Test
  void GivenAnEmployee_WhenReadingTheirOwnDeclarations_ThenBothAreReturned() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    TenantActor themself = new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE, Optional.of(ada));
    service.declareUnavailability(themself, new DeclareUnavailabilityCommand(ACME, ada, MONDAY));
    service.statePreference(
        themself, new StatePreferenceCommand(ACME, ada, TUESDAY, PreferenceStrength.PREFERS));

    assertThat(service.findUnavailability(themself, ACME, ada)).containsExactly(MONDAY);
    assertThat(service.findPreferences(themself, ACME, ada))
        .containsExactly(new ShiftPreference(TUESDAY, PreferenceStrength.PREFERS));
  }

  @Test
  void GivenAnAdmin_WhenReadingSomebodysDeclarations_ThenTheyAreReturned() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    service.declareUnavailability(admin(ACME), new DeclareUnavailabilityCommand(ACME, ada, MONDAY));

    assertThat(service.findUnavailability(admin(ACME), ACME, ada)).containsExactly(MONDAY);
  }

  @Test
  void GivenAScheduler_WhenReadingSomebodysDeclarations_ThenTheyAreReturned() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    service.statePreference(
        admin(ACME), new StatePreferenceCommand(ACME, ada, TUESDAY, PreferenceStrength.PREFERS));

    assertThat(service.findPreferences(actor(ACME, Role.SCHEDULER), ACME, ada))
        .containsExactly(new ShiftPreference(TUESDAY, PreferenceStrength.PREFERS));
  }

  @Test
  void GivenAColleague_WhenReadingSomebodyElsesUnavailability_ThenItIsRefused() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    EmployeeId grace = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Grace"));
    TenantActor gracesAccount =
        new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE, Optional.of(grace));

    assertThatThrownBy(() -> service.findUnavailability(gracesAccount, ACME, ada))
        .describedAs("unavailability is personal data; an employee has no reason to read another's")
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAColleague_WhenReadingSomebodyElsesPreferences_ThenItIsRefused() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    EmployeeId grace = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Grace"));
    TenantActor gracesAccount =
        new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE, Optional.of(grace));

    assertThatThrownBy(() -> service.findPreferences(gracesAccount, ACME, ada))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAnotherOrganisationsEmployee_WhenReadingTheirUnavailabilityThroughOurs_ThenUnknown() {
    EmployeeId mole = service.addEmployee(admin(RIVAL), new AddEmployeeCommand(RIVAL, "Mole"));

    assertThatThrownBy(() -> service.findUnavailability(admin(ACME), ACME, mole))
        .describedAs("knowing an identity from another tenant must not be enough to read it")
        .isInstanceOf(UnknownEmployeeException.class);
  }

  @Test
  void GivenAnotherOrganisationsEmployee_WhenReadingTheirPreferencesThroughOurs_ThenUnknown() {
    EmployeeId mole = service.addEmployee(admin(RIVAL), new AddEmployeeCommand(RIVAL, "Mole"));

    assertThatThrownBy(() -> service.findPreferences(admin(ACME), ACME, mole))
        .isInstanceOf(UnknownEmployeeException.class);
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
   * for them are the same change to the same subject, and only the actor tells them apart — which
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

    // Invented: contracted hours are a term of an individual agreement, never a statutory figure.
    service.setContractTerms(
        manager, new SetContractTermsCommand(ACME, subject, Duration.ofHours(20)));

    assertThat(audit.of(WorkforceChange.CONTRACT_SET))
        .containsExactly(
            new AuditedChange(ACME, manager.id(), WorkforceChange.CONTRACT_SET, subject.value()));
  }

  @Test
  void GivenAnAdminGrantingAQualification_WhenRecorded_ThenTheSubjectIsTheEmployee() {
    TenantActor admin = admin(ACME);
    EmployeeId subject = service.addEmployee(admin, new AddEmployeeCommand(ACME, "Ada"));
    QualificationId forklift = QualificationId.newId();
    qualifications.save(new Qualification(forklift, ACME, "Forklift"));

    service.grantQualification(admin, new GrantQualificationCommand(ACME, subject, forklift));

    assertThat(audit.of(WorkforceChange.QUALIFICATION_GRANTED))
        .describedAs("the employee changed, not the qualification they were granted")
        .containsExactly(
            new AuditedChange(
                ACME, admin.id(), WorkforceChange.QUALIFICATION_GRANTED, subject.value()));
  }

  /**
   * A preference is not unavailability — it never blocks an assignment — but stating one still
   * changes somebody's record, so it is still somebody's act to account for.
   */
  @Test
  void GivenSomeoneStatingAPreference_WhenRecorded_ThenTheActorIsThemAndTheSubjectIsTheirRecord() {
    EmployeeId subject = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    TenantActor themself =
        new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE, Optional.of(subject));

    service.statePreference(
        themself,
        new StatePreferenceCommand(ACME, subject, MONDAY, PreferenceStrength.PREFERS_NOT));

    assertThat(audit.of(WorkforceChange.PREFERENCE_STATED))
        .containsExactly(
            new AuditedChange(
                ACME, themself.id(), WorkforceChange.PREFERENCE_STATED, subject.value()));
  }

  @Test
  void GivenAQualificationOfAnotherTenant_WhenGrantingIsRefused_ThenNothingIsRecorded() {
    EmployeeId ours = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    QualificationId theirs = QualificationId.newId();
    qualifications.save(new Qualification(theirs, RIVAL, "Their Ticket"));

    assertThatThrownBy(
            () ->
                service.grantQualification(
                    admin(ACME), new GrantQualificationCommand(ACME, ours, theirs)))
        .isInstanceOf(UnknownQualificationException.class);

    assertThat(audit.of(WorkforceChange.QUALIFICATION_GRANTED)).isEmpty();
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

  // --- ADR-030: the platform operator is refused by every tenant use case -------------------
  //
  // One test per driving-port method rather than a table. Each is a permission decision, and a
  // table would read as a list rather than as a set of security properties.

  @Test
  void GivenTheOperator_WhenAddingAnEmployee_ThenItIsRefused() {
    assertThatThrownBy(() -> service.addEmployee(anOperator(), new AddEmployeeCommand(ACME, "Ada")))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenGrantingAQualification_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.grantQualification(
                    anOperator(),
                    new GrantQualificationCommand(
                        ACME, EmployeeId.newId(), QualificationId.newId())))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenSettingContractTerms_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.setContractTerms(
                    anOperator(),
                    new SetContractTermsCommand(ACME, EmployeeId.newId(), Duration.ofHours(20))))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenDeclaringUnavailability_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.declareUnavailability(
                    anOperator(),
                    new DeclareUnavailabilityCommand(ACME, EmployeeId.newId(), MONDAY)))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenStatingAPreference_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.statePreference(
                    anOperator(),
                    new StatePreferenceCommand(
                        ACME, EmployeeId.newId(), MONDAY, PreferenceStrength.PREFERS_NOT)))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenReadingAnEmployee_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findEmployee(anOperator(), ACME, EmployeeId.newId()))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenReadingUnavailability_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findUnavailability(anOperator(), ACME, EmployeeId.newId()))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenReadingPreferences_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findPreferences(anOperator(), ACME, EmployeeId.newId()))
        .isInstanceOf(NotATenantMemberException.class);
  }

  private static Operator anOperator() {
    return new Operator(UserId.newId());
  }
}
