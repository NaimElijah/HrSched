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
import io.github.naimelijah.hrsched.workforce.application.port.in.WithdrawPreferenceCommand;
import io.github.naimelijah.hrsched.workforce.application.port.in.WithdrawUnavailabilityCommand;
import io.github.naimelijah.hrsched.workforce.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.workforce.application.port.out.EmployeeRepository;
import io.github.naimelijah.hrsched.workforce.application.port.out.QualificationRepository;
import io.github.naimelijah.hrsched.workforce.application.port.out.WorkforceChange;
import io.github.naimelijah.hrsched.workforce.domain.model.Employee;
import io.github.naimelijah.hrsched.workforce.domain.model.PreferenceStrength;
import io.github.naimelijah.hrsched.workforce.domain.model.Qualification;
import io.github.naimelijah.hrsched.workforce.domain.model.ShiftPreference;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownDeclarationException;
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

  /**
   * What somebody said about when they would like to work travels with them too, split by how they
   * feel, because the preference objective counts the two differently (ADR-010).
   */
  @Test
  void GivenAnEmployeeWithPreferences_WhenPublished_ThenEachStrengthTravelsAsItsOwnSpans() {
    EmployeeId id = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    service.statePreference(
        admin(ACME), new StatePreferenceCommand(ACME, id, MONDAY, PreferenceStrength.PREFERS));
    service.statePreference(
        admin(ACME), new StatePreferenceCommand(ACME, id, TUESDAY, PreferenceStrength.PREFERS_NOT));

    RosterableEmployee published = service.findRosterable(admin(ACME), ACME).getFirst();

    assertThat(published.wouldLikeToWork()).containsExactly(MONDAY);
    assertThat(published.wouldRatherNotWork()).containsExactly(TUESDAY);
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

  /**
   * The organisation is checked against the actor, not only used to scope the lookup. Over HTTP it
   * always comes from the token, so only the port can be handed a mismatched one.
   */
  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenReadingSomebodysUnavailability_ThenItIsRefused() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));

    assertThatThrownBy(() -> service.findUnavailability(admin(RIVAL), ACME, ada))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenReadingSomebodysPreferences_ThenItIsRefused() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));

    assertThatThrownBy(() -> service.findPreferences(admin(RIVAL), ACME, ada))
        .isInstanceOf(CrossTenantAccessException.class);
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

  // --- F26: withdrawing a declaration (ADR-040) ---------------------------------------------

  @Test
  void GivenAnEmployee_WhenWithdrawingTheirOwnUnavailability_ThenItIsNoLongerDeclared() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    TenantActor themself = new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE, Optional.of(ada));
    service.declareUnavailability(themself, new DeclareUnavailabilityCommand(ACME, ada, MONDAY));

    service.withdrawUnavailability(themself, new WithdrawUnavailabilityCommand(ACME, ada, MONDAY));

    assertThat(service.findUnavailability(themself, ACME, ada)).isEmpty();
  }

  @Test
  void GivenAnAdmin_WhenWithdrawingSomebodysUnavailability_ThenItIsNoLongerDeclared() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    service.declareUnavailability(admin(ACME), new DeclareUnavailabilityCommand(ACME, ada, MONDAY));

    service.withdrawUnavailability(
        admin(ACME), new WithdrawUnavailabilityCommand(ACME, ada, MONDAY));

    assertThat(service.findUnavailability(admin(ACME), ACME, ada)).isEmpty();
  }

  @Test
  void GivenAScheduler_WhenWithdrawingSomebodysPreference_ThenItIsNoLongerStated() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    TenantActor scheduler = actor(ACME, Role.SCHEDULER);
    service.statePreference(
        scheduler, new StatePreferenceCommand(ACME, ada, TUESDAY, PreferenceStrength.PREFERS));

    service.withdrawPreference(
        scheduler, new WithdrawPreferenceCommand(ACME, ada, TUESDAY, PreferenceStrength.PREFERS));

    assertThat(service.findPreferences(scheduler, ACME, ada)).isEmpty();
  }

  @Test
  void GivenAColleague_WhenWithdrawingSomebodyElsesUnavailability_ThenItIsRefusedAndKept() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    EmployeeId grace = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Grace"));
    TenantActor gracesAccount =
        new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE, Optional.of(grace));
    service.declareUnavailability(admin(ACME), new DeclareUnavailabilityCommand(ACME, ada, MONDAY));

    assertThatThrownBy(
            () ->
                service.withdrawUnavailability(
                    gracesAccount, new WithdrawUnavailabilityCommand(ACME, ada, MONDAY)))
        .describedAs("withdrawing somebody's absence would put them on a roster they cannot work")
        .isInstanceOf(NotPermittedException.class);
    assertThat(service.findUnavailability(admin(ACME), ACME, ada)).containsExactly(MONDAY);
  }

  @Test
  void GivenAColleague_WhenWithdrawingSomebodyElsesPreference_ThenItIsRefusedAndKept() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    EmployeeId grace = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Grace"));
    TenantActor gracesAccount =
        new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE, Optional.of(grace));
    service.statePreference(
        admin(ACME), new StatePreferenceCommand(ACME, ada, TUESDAY, PreferenceStrength.PREFERS));

    assertThatThrownBy(
            () ->
                service.withdrawPreference(
                    gracesAccount,
                    new WithdrawPreferenceCommand(ACME, ada, TUESDAY, PreferenceStrength.PREFERS)))
        .isInstanceOf(NotPermittedException.class);
    assertThat(service.findPreferences(admin(ACME), ACME, ada))
        .containsExactly(new ShiftPreference(TUESDAY, PreferenceStrength.PREFERS));
  }

  @Test
  void GivenAnAdmin_WhenWithdrawingSomebodysPreference_ThenItIsNoLongerStated() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    service.statePreference(
        admin(ACME), new StatePreferenceCommand(ACME, ada, TUESDAY, PreferenceStrength.PREFERS));

    service.withdrawPreference(
        admin(ACME), new WithdrawPreferenceCommand(ACME, ada, TUESDAY, PreferenceStrength.PREFERS));

    assertThat(service.findPreferences(admin(ACME), ACME, ada)).isEmpty();
  }

  @Test
  void GivenAScheduler_WhenWithdrawingSomebodysUnavailability_ThenItIsNoLongerDeclared() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    TenantActor scheduler = actor(ACME, Role.SCHEDULER);
    service.declareUnavailability(scheduler, new DeclareUnavailabilityCommand(ACME, ada, MONDAY));

    service.withdrawUnavailability(scheduler, new WithdrawUnavailabilityCommand(ACME, ada, MONDAY));

    assertThat(service.findUnavailability(scheduler, ACME, ada)).isEmpty();
  }

  @Test
  void GivenAnotherOrganisationsEmployee_WhenWithdrawingTheirPreferenceThroughOurs_ThenUnknown() {
    EmployeeId mole = service.addEmployee(admin(RIVAL), new AddEmployeeCommand(RIVAL, "Mole"));
    service.statePreference(
        admin(RIVAL), new StatePreferenceCommand(RIVAL, mole, TUESDAY, PreferenceStrength.PREFERS));

    assertThatThrownBy(
            () ->
                service.withdrawPreference(
                    admin(ACME),
                    new WithdrawPreferenceCommand(ACME, mole, TUESDAY, PreferenceStrength.PREFERS)))
        .describedAs("knowing an identity from another tenant must not be enough to act on it")
        .isInstanceOf(UnknownEmployeeException.class);
    assertThat(service.findPreferences(admin(RIVAL), RIVAL, mole))
        .containsExactly(new ShiftPreference(TUESDAY, PreferenceStrength.PREFERS));
  }

  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenWithdrawingSomebodysUnavailability_ThenItIsRefused() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    service.declareUnavailability(admin(ACME), new DeclareUnavailabilityCommand(ACME, ada, MONDAY));

    assertThatThrownBy(
            () ->
                service.withdrawUnavailability(
                    admin(RIVAL), new WithdrawUnavailabilityCommand(ACME, ada, MONDAY)))
        .isInstanceOf(CrossTenantAccessException.class);
    assertThat(service.findUnavailability(admin(ACME), ACME, ada)).containsExactly(MONDAY);
  }

  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenWithdrawingSomebodysPreference_ThenItIsRefused() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));

    assertThatThrownBy(
            () ->
                service.withdrawPreference(
                    admin(RIVAL),
                    new WithdrawPreferenceCommand(ACME, ada, TUESDAY, PreferenceStrength.PREFERS)))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void
      GivenAnotherOrganisationsEmployee_WhenWithdrawingTheirUnavailabilityThroughOurs_ThenUnknown() {
    EmployeeId mole = service.addEmployee(admin(RIVAL), new AddEmployeeCommand(RIVAL, "Mole"));
    service.declareUnavailability(
        admin(RIVAL), new DeclareUnavailabilityCommand(RIVAL, mole, MONDAY));

    assertThatThrownBy(
            () ->
                service.withdrawUnavailability(
                    admin(ACME), new WithdrawUnavailabilityCommand(ACME, mole, MONDAY)))
        .describedAs("knowing an identity from another tenant must not be enough to act on it")
        .isInstanceOf(UnknownEmployeeException.class);
    assertThat(service.findUnavailability(admin(RIVAL), RIVAL, mole)).containsExactly(MONDAY);
  }

  @Test
  void GivenASpanNeverDeclared_WhenWithdrawingIt_ThenItIsRefusedAndNothingIsRecorded() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    service.declareUnavailability(admin(ACME), new DeclareUnavailabilityCommand(ACME, ada, MONDAY));

    assertThatThrownBy(
            () ->
                service.withdrawUnavailability(
                    admin(ACME), new WithdrawUnavailabilityCommand(ACME, ada, TUESDAY)))
        .isInstanceOf(UnknownDeclarationException.class);
    assertThat(audit.of(WorkforceChange.UNAVAILABILITY_WITHDRAWN)).isEmpty();
    assertThat(service.findUnavailability(admin(ACME), ACME, ada)).containsExactly(MONDAY);
  }

  /** The same span with the other strength is a different statement, and is not this one. */
  @Test
  void GivenAPreferenceNeverStated_WhenWithdrawingIt_ThenItIsRefusedAndNothingIsRecorded() {
    EmployeeId ada = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    service.statePreference(
        admin(ACME),
        new StatePreferenceCommand(ACME, ada, TUESDAY, PreferenceStrength.PREFERS_NOT));

    assertThatThrownBy(
            () ->
                service.withdrawPreference(
                    admin(ACME),
                    new WithdrawPreferenceCommand(ACME, ada, TUESDAY, PreferenceStrength.PREFERS)))
        .isInstanceOf(UnknownDeclarationException.class);
    assertThat(audit.of(WorkforceChange.PREFERENCE_WITHDRAWN)).isEmpty();
    assertThat(service.findPreferences(admin(ACME), ACME, ada))
        .containsExactly(new ShiftPreference(TUESDAY, PreferenceStrength.PREFERS_NOT));
  }

  /** As with declaring: only the actor tells somebody's own act from a manager's (ADR-012). */
  @Test
  void
      GivenSomeoneWithdrawingTheirOwnUnavailability_WhenRecorded_ThenTheActorIsThemNotTheirManager() {
    EmployeeId subject = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    TenantActor themself =
        new TenantActor(UserId.newId(), ACME, Role.EMPLOYEE, Optional.of(subject));
    service.declareUnavailability(
        themself, new DeclareUnavailabilityCommand(ACME, subject, MONDAY));

    service.withdrawUnavailability(
        themself, new WithdrawUnavailabilityCommand(ACME, subject, MONDAY));

    assertThat(audit.of(WorkforceChange.UNAVAILABILITY_WITHDRAWN))
        .containsExactly(
            new AuditedChange(
                ACME, themself.id(), WorkforceChange.UNAVAILABILITY_WITHDRAWN, subject.value()));
  }

  @Test
  void GivenAManagerWithdrawingSomebodysPreference_WhenRecorded_ThenTheSubjectIsTheEmployee() {
    EmployeeId subject = service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
    TenantActor manager = admin(ACME);
    service.statePreference(
        manager,
        new StatePreferenceCommand(ACME, subject, TUESDAY, PreferenceStrength.PREFERS_NOT));

    service.withdrawPreference(
        manager,
        new WithdrawPreferenceCommand(ACME, subject, TUESDAY, PreferenceStrength.PREFERS_NOT));

    assertThat(audit.of(WorkforceChange.PREFERENCE_WITHDRAWN))
        .containsExactly(
            new AuditedChange(
                ACME, manager.id(), WorkforceChange.PREFERENCE_WITHDRAWN, subject.value()));
  }

  // --- #33: a change holds the employee from its read (ADR-041)
  // -----------------------------------
  //
  // An employee is stored whole, so a change that read them plainly would store over a concurrent
  // change to the same person - an Admin granting a qualification while they declare an absence,
  // say. Each change below reads them for update instead, so the second waits and reads the first.
  // That the read really waits is proven against PostgreSQL, in JdbcEmployeeRepositoryTest.

  private EmployeeId anEmployeeOfAcme() {
    return service.addEmployee(admin(ACME), new AddEmployeeCommand(ACME, "Ada"));
  }

  @Test
  void GivenAnEmployee_WhenGrantedAQualification_ThenTheyAreReadForUpdate() {
    EmployeeId ada = anEmployeeOfAcme();
    QualificationId ticket = QualificationId.newId();
    qualifications.save(new Qualification(ticket, ACME, "Ticket"));

    service.grantQualification(admin(ACME), new GrantQualificationCommand(ACME, ada, ticket));

    assertThat(employees.heldForUpdate()).containsExactly(ada);
  }

  @Test
  void GivenAnEmployee_WhenTheirContractIsSet_ThenTheyAreReadForUpdate() {
    EmployeeId ada = anEmployeeOfAcme();

    service.setContractTerms(
        admin(ACME), new SetContractTermsCommand(ACME, ada, Duration.ofHours(30)));

    assertThat(employees.heldForUpdate()).containsExactly(ada);
  }

  @Test
  void GivenAnEmployee_WhenDeclaringUnavailability_ThenTheyAreReadForUpdate() {
    EmployeeId ada = anEmployeeOfAcme();

    service.declareUnavailability(admin(ACME), new DeclareUnavailabilityCommand(ACME, ada, MONDAY));

    assertThat(employees.heldForUpdate()).containsExactly(ada);
  }

  @Test
  void GivenAnEmployee_WhenStatingAPreference_ThenTheyAreReadForUpdate() {
    EmployeeId ada = anEmployeeOfAcme();

    service.statePreference(
        admin(ACME), new StatePreferenceCommand(ACME, ada, MONDAY, PreferenceStrength.PREFERS));

    assertThat(employees.heldForUpdate()).containsExactly(ada);
  }

  @Test
  void GivenADeclaredAbsence_WhenWithdrawingIt_ThenTheEmployeeIsReadForUpdate() {
    EmployeeId ada = anEmployeeOfAcme();
    service.declareUnavailability(admin(ACME), new DeclareUnavailabilityCommand(ACME, ada, MONDAY));

    service.withdrawUnavailability(
        admin(ACME), new WithdrawUnavailabilityCommand(ACME, ada, MONDAY));

    assertThat(employees.heldForUpdate()).containsExactly(ada, ada);
  }

  @Test
  void GivenAStatedPreference_WhenWithdrawingIt_ThenTheEmployeeIsReadForUpdate() {
    EmployeeId ada = anEmployeeOfAcme();
    service.statePreference(
        admin(ACME), new StatePreferenceCommand(ACME, ada, MONDAY, PreferenceStrength.PREFERS));

    service.withdrawPreference(
        admin(ACME), new WithdrawPreferenceCommand(ACME, ada, MONDAY, PreferenceStrength.PREFERS));

    assertThat(employees.heldForUpdate()).containsExactly(ada, ada);
  }

  /**
   * Reading back what somebody declared changes nothing, so it holds nothing: it runs read-only,
   * where a read for update would be refused.
   */
  @Test
  void GivenAnEmployee_WhenTheirDeclarationsAreRead_ThenNothingIsHeld() {
    EmployeeId ada = anEmployeeOfAcme();

    service.findUnavailability(admin(ACME), ACME, ada);
    service.findPreferences(admin(ACME), ACME, ada);

    assertThat(employees.heldForUpdate()).isEmpty();
  }

  /** In-memory stand-in for the driven port, scoped by organisation exactly as the real one is. */
  private static final class StubEmployeeRepository implements EmployeeRepository {

    private final Map<EmployeeId, Employee> saved = new HashMap<>();
    private final List<EmployeeId> heldForUpdate = new ArrayList<>();

    @Override
    public void save(Employee employee) {
      saved.put(employee.id(), employee);
    }

    @Override
    public Optional<Employee> findById(OrganisationId organisation, EmployeeId id) {
      return Optional.ofNullable(saved.get(id))
          .filter(employee -> employee.organisation().equals(organisation));
    }

    /**
     * Holds nothing - one thread, one map - but records who was read for update, so a test can see
     * a change hold the employee it is about to store. That the read really makes a second change
     * wait is proven against PostgreSQL, in {@code JdbcEmployeeRepositoryTest}.
     */
    @Override
    public Optional<Employee> findForUpdate(OrganisationId organisation, EmployeeId id) {
      heldForUpdate.add(id);
      return findById(organisation, id);
    }

    /**
     * @return everybody read for update so far, in the order they were read
     */
    List<EmployeeId> heldForUpdate() {
      return List.copyOf(heldForUpdate);
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

  @Test
  void GivenTheOperator_WhenWithdrawingUnavailability_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.withdrawUnavailability(
                    anOperator(),
                    new WithdrawUnavailabilityCommand(ACME, EmployeeId.newId(), MONDAY)))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenTheOperator_WhenWithdrawingAPreference_ThenItIsRefused() {
    assertThatThrownBy(
            () ->
                service.withdrawPreference(
                    anOperator(),
                    new WithdrawPreferenceCommand(
                        ACME, EmployeeId.newId(), MONDAY, PreferenceStrength.PREFERS)))
        .isInstanceOf(NotATenantMemberException.class);
  }

  private static Operator anOperator() {
    return new Operator(UserId.newId());
  }
}
