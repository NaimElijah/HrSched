package io.github.naimelijah.hrsched.access.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.access.application.port.in.ChangeMembershipCommand;
import io.github.naimelijah.hrsched.access.application.port.in.CreateAccountCommand;
import io.github.naimelijah.hrsched.access.application.port.in.PublishedAccount;
import io.github.naimelijah.hrsched.access.application.port.in.ResetPasswordCommand;
import io.github.naimelijah.hrsched.access.application.port.in.SetAccountActiveCommand;
import io.github.naimelijah.hrsched.access.application.port.out.AccessChange;
import io.github.naimelijah.hrsched.access.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.CannotResetOwnPasswordException;
import io.github.naimelijah.hrsched.access.domain.model.EmployeeAlreadyLinkedException;
import io.github.naimelijah.hrsched.access.domain.model.LastActiveAdminException;
import io.github.naimelijah.hrsched.access.domain.model.LockoutPolicy;
import io.github.naimelijah.hrsched.access.domain.model.NotAnEmployeeHereException;
import io.github.naimelijah.hrsched.access.domain.model.PasswordPolicy;
import io.github.naimelijah.hrsched.access.domain.model.SessionRevocation;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.SignInNameTakenException;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.access.domain.model.UnacceptablePasswordException;
import io.github.naimelijah.hrsched.access.domain.model.UnknownAccountException;
import io.github.naimelijah.hrsched.access.domain.model.UnlinkedEmployeeAccountException;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.NotATenantMemberException;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Specification of account administration, driven without Spring (requirement F15).
 *
 * <p>Named for what it covers rather than after the class, because {@code AccountServiceTest}
 * already specifies bootstrapping the platform operator and the two have nothing to say to each
 * other: one is about a deployment starting up, this one is about an Admin administering
 * colleagues.
 *
 * <p>Two rules here are the ones whose failure is a security incident, and neither is tabled into a
 * parameterised row. An Admin acts only inside their own organisation, and the platform operator
 * acts inside none (ADR-030); both are asserted one test at a time, so that the test that fails
 * names the identity that got through.
 *
 * <p>The third is {@link LastActiveAdminException}, which is not about disclosure but about
 * recovery: an organisation with no active Admin cannot be repaired by anybody, because the only
 * identity outside it is refused entry.
 */
class AccountAdministrationTest {

  /** Invented for these tests, and short enough that a shorter password is easy to write. */
  private static final int MINIMUM_PASSWORD_LENGTH = 12;

  /** Invented, and long enough to satisfy the policy above. */
  private static final String A_PASSWORD = "an invented temporary password";

  /** Invented, and deliberately below the policy. */
  private static final String TOO_SHORT = "abbreviated";

  private static final Instant NOW = Instant.parse("2026-03-01T09:00:00Z");

  /** Invented, and set to one so a single failure is enough to arrange a locked account. */
  private static final LockoutPolicy LOCK_ON_THE_FIRST_FAILURE =
      new LockoutPolicy(1, Duration.ofMinutes(15));

  private final OrganisationId acme = OrganisationId.newId();
  private final OrganisationId rival = OrganisationId.newId();

  private final StubAccountRepository accounts = new StubAccountRepository();
  private final RecordingSessionRevocations sessions = new RecordingSessionRevocations();
  private final StubEmployeeDirectory employees = new StubEmployeeDirectory();
  private final StubPasswordHasher passwords = new StubPasswordHasher();
  private final RecordingAuditTrail audit = new RecordingAuditTrail();

  private final AccountService service =
      new AccountService(
          accounts,
          sessions,
          employees,
          passwords,
          new PasswordPolicy(MINIMUM_PASSWORD_LENGTH, passwords.maximumBytes()),
          audit,
          Clock.fixed(NOW, ZoneOffset.UTC));

  private TenantActor admin(OrganisationId organisation) {
    return new TenantActor(UserId.newId(), organisation, Role.ADMIN);
  }

  private static TenantActor actor(OrganisationId organisation, Role role) {
    return new TenantActor(UserId.newId(), organisation, role);
  }

  private static String anInventedSignInName() {
    return "colleague-%s@example.com".formatted(UserId.newId().value());
  }

  private CreateAccountCommand creating(OrganisationId organisation, Role role) {
    return new CreateAccountCommand(
        organisation, anInventedSignInName(), A_PASSWORD, role, Optional.empty());
  }

  /**
   * An account that already exists, so a test can change it rather than create it first. An
   * Employee's is linked to somebody its organisation employs, as R34 requires; every other role's
   * is linked to nobody.
   */
  private Account existing(OrganisationId organisation, Role role, boolean active) {
    Optional<EmployeeId> link =
        role == Role.EMPLOYEE ? Optional.of(employees.employedBy(organisation)) : Optional.empty();
    Account account =
        new Account(
            UserId.newId(),
            new SignInName(anInventedSignInName()),
            passwords.hash(A_PASSWORD),
            new TenantMembership(organisation, role, link),
            false,
            NOW);
    return accounts.given(active ? account : account.disabled());
  }

  /**
   * An Employee account stored linked to nobody before R34, as V9 left it: disabled. Only the
   * rebuilding constructor can make one, because no change may.
   */
  private Account migrated(OrganisationId organisation) {
    return accounts.given(
        new Account(
            UserId.newId(),
            new SignInName(anInventedSignInName()),
            passwords.hash(A_PASSWORD),
            new TenantMembership(organisation, Role.EMPLOYEE, Optional.empty()),
            false,
            false,
            0,
            Optional.empty(),
            NOW));
  }

  // Creating an account

  @Test
  void GivenAnAdmin_WhenCreatingAnAccount_ThenItIsListedAndMustChangeItsPassword() {
    TenantActor admin = admin(acme);

    UserId created = service.createAccount(admin, creating(acme, Role.SCHEDULER));

    assertThat(service.findAccounts(admin, acme))
        .singleElement()
        .returns(created, PublishedAccount::id)
        .returns(Role.SCHEDULER, PublishedAccount::role)
        .returns(true, PublishedAccount::active)
        .returns(true, PublishedAccount::passwordChangeRequired);
  }

  @Test
  void GivenAnAdmin_WhenCreatingAnAccount_ThenThePasswordIsStoredHashed() {
    String signInName = anInventedSignInName();

    service.createAccount(
        admin(acme),
        new CreateAccountCommand(acme, signInName, A_PASSWORD, Role.SCHEDULER, Optional.empty()));

    assertThat(accounts.findBySignInName(new SignInName(signInName)).orElseThrow().passwordHash())
        .isEqualTo(passwords.hash(A_PASSWORD));
  }

  @Test
  void GivenASchedulerRole_WhenCreatingAnAccount_ThenItIsRefused() {
    assertThatThrownBy(
            () -> service.createAccount(actor(acme, Role.SCHEDULER), creating(acme, Role.EMPLOYEE)))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenAnEmployeeRole_WhenCreatingAnAccount_ThenItIsRefused() {
    assertThatThrownBy(
            () -> service.createAccount(actor(acme, Role.EMPLOYEE), creating(acme, Role.EMPLOYEE)))
        .isInstanceOf(NotPermittedException.class);
  }

  /** The operator administers the register of tenants, never the people inside one (ADR-030). */
  @Test
  void GivenTheOperator_WhenCreatingAnAccount_ThenItIsRefused() {
    assertThatThrownBy(
            () -> service.createAccount(new Operator(UserId.newId()), creating(acme, Role.ADMIN)))
        .isInstanceOf(NotATenantMemberException.class);
  }

  /** The failure that would let one customer plant an account inside another's organisation. */
  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenCreatingAnAccount_ThenItIsRefused() {
    assertThatThrownBy(() -> service.createAccount(admin(rival), creating(acme, Role.ADMIN)))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenATemporaryPasswordBelowThePolicy_WhenCreatingAnAccount_ThenNothingIsCreated() {
    TenantActor admin = admin(acme);

    assertThatThrownBy(
            () ->
                service.createAccount(
                    admin,
                    new CreateAccountCommand(
                        acme, anInventedSignInName(), TOO_SHORT, Role.SCHEDULER, Optional.empty())))
        .isInstanceOf(UnacceptablePasswordException.class)
        .hasMessageNotContaining(TOO_SHORT);

    assertThat(service.findAccounts(admin, acme)).isEmpty();
    assertThat(audit.changes()).isEmpty();
  }

  @Test
  void GivenASignInNameAlreadyTaken_WhenCreatingAnAccount_ThenItIsRefused() {
    TenantActor admin = admin(acme);
    String taken = anInventedSignInName();
    service.createAccount(
        admin, new CreateAccountCommand(acme, taken, A_PASSWORD, Role.ADMIN, Optional.empty()));
    audit.forget();

    assertThatThrownBy(
            () ->
                service.createAccount(
                    admin,
                    new CreateAccountCommand(
                        acme, taken, A_PASSWORD, Role.SCHEDULER, Optional.empty())))
        .isInstanceOf(SignInNameTakenException.class);

    assertThat(audit.changes()).isEmpty();
  }

  /**
   * The link is what answers "is this me?" for self-service (ADR-012), so a link to somebody the
   * organisation does not employ would hand an account another organisation's staff rights.
   */
  @Test
  void GivenAnEmployeeThisOrganisationDoesNotHave_WhenLinkingAnAccountToThem_ThenItIsRefused() {
    EmployeeId stranger = EmployeeId.newId();

    assertThatThrownBy(
            () ->
                service.createAccount(
                    admin(acme),
                    new CreateAccountCommand(
                        acme,
                        anInventedSignInName(),
                        A_PASSWORD,
                        Role.EMPLOYEE,
                        Optional.of(stranger))))
        .isInstanceOf(NotAnEmployeeHereException.class);
  }

  @Test
  void GivenAnEmployeeWhoAlreadyHasAnAccount_WhenCreatingASecond_ThenItIsRefused() {
    TenantActor admin = admin(acme);
    EmployeeId ada = employees.employedBy(acme);
    service.createAccount(
        admin,
        new CreateAccountCommand(
            acme, anInventedSignInName(), A_PASSWORD, Role.EMPLOYEE, Optional.of(ada)));

    assertThatThrownBy(
            () ->
                service.createAccount(
                    admin,
                    new CreateAccountCommand(
                        acme, anInventedSignInName(), A_PASSWORD, Role.EMPLOYEE, Optional.of(ada))))
        .isInstanceOf(EmployeeAlreadyLinkedException.class);
  }

  // Listing accounts

  @Test
  void GivenAnotherOrganisationsAccounts_WhenTheAdminLists_ThenTheyAreNotReturned() {
    existing(acme, Role.SCHEDULER, true);
    existing(rival, Role.ADMIN, true);
    existing(rival, Role.EMPLOYEE, true);

    assertThat(service.findAccounts(admin(acme), acme)).hasSize(1);
  }

  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenListingAccounts_ThenItIsRefused() {
    existing(acme, Role.SCHEDULER, true);

    assertThatThrownBy(() -> service.findAccounts(admin(rival), acme))
        .isInstanceOf(CrossTenantAccessException.class);
  }

  @Test
  void GivenAnEmployeeRole_WhenListingAccounts_ThenItIsRefused() {
    assertThatThrownBy(() -> service.findAccounts(actor(acme, Role.EMPLOYEE), acme))
        .isInstanceOf(NotPermittedException.class);
  }

  @Test
  void GivenTheOperator_WhenListingAnOrganisationsAccounts_ThenItIsRefused() {
    existing(acme, Role.ADMIN, true);

    assertThatThrownBy(() -> service.findAccounts(new Operator(UserId.newId()), acme))
        .isInstanceOf(NotATenantMemberException.class);
  }

  @Test
  void GivenALockedAccount_WhenTheAdminLists_ThenItIsShownAsLocked() {
    Account locked = existing(acme, Role.EMPLOYEE, true);
    accounts.save(locked.recordFailedSignIn(NOW, LOCK_ON_THE_FIRST_FAILURE));

    assertThat(service.findAccounts(admin(acme), acme))
        .singleElement()
        .returns(true, PublishedAccount::locked);
  }

  @Test
  void GivenAnAccountNobodyHasSignedInTo_WhenTheAdminLists_ThenItIsNotLocked() {
    existing(acme, Role.EMPLOYEE, true);

    assertThat(service.findAccounts(admin(acme), acme))
        .singleElement()
        .returns(false, PublishedAccount::locked);
  }

  // Changing what an account is

  @Test
  void GivenAnAccount_WhenItsRoleIsChanged_ThenItHoldsTheNewRole() {
    TenantActor admin = admin(acme);
    existing(acme, Role.ADMIN, true);
    Account scheduler = existing(acme, Role.SCHEDULER, true);

    service.changeMembership(
        admin, new ChangeMembershipCommand(acme, scheduler.id(), Role.ADMIN, Optional.empty()));

    assertThat(accounts.find(acme, scheduler.id()).orElseThrow().membership())
        .isEqualTo(new TenantMembership(acme, Role.ADMIN, Optional.empty()));
  }

  /**
   * From an unlinked Scheduler to a linked Employee: the change that would pass through an unlinked
   * Employee if the role and the link were set one after the other.
   */
  @Test
  void GivenAnAccount_WhenItIsLinkedToAnEmployee_ThenTheLinkIsKept() {
    TenantActor admin = admin(acme);
    existing(acme, Role.ADMIN, true);
    Account account = existing(acme, Role.SCHEDULER, true);
    EmployeeId ada = employees.employedBy(acme);

    service.changeMembership(
        admin, new ChangeMembershipCommand(acme, account.id(), Role.EMPLOYEE, Optional.of(ada)));

    assertThat(accounts.find(acme, account.id()).orElseThrow().membership())
        .isEqualTo(new TenantMembership(acme, Role.EMPLOYEE, Optional.of(ada)));
  }

  @Test
  void GivenTheLastActiveAdmin_WhenDemoted_ThenItIsRefused() {
    Account only = existing(acme, Role.ADMIN, true);

    assertThatThrownBy(
            () ->
                service.changeMembership(
                    admin(acme),
                    new ChangeMembershipCommand(acme, only.id(), Role.SCHEDULER, Optional.empty())))
        .isInstanceOf(LastActiveAdminException.class);

    assertThat(accounts.find(acme, only.id()).orElseThrow().membership())
        .isEqualTo(new TenantMembership(acme, Role.ADMIN, Optional.empty()));
  }

  @Test
  void GivenTwoActiveAdmins_WhenOneIsDemoted_ThenItIsAllowed() {
    Account first = existing(acme, Role.ADMIN, true);
    existing(acme, Role.ADMIN, true);

    service.changeMembership(
        admin(acme),
        new ChangeMembershipCommand(acme, first.id(), Role.SCHEDULER, Optional.empty()));

    assertThat(accounts.find(acme, first.id()).orElseThrow().membership())
        .isEqualTo(new TenantMembership(acme, Role.SCHEDULER, Optional.empty()));
  }

  /** A disabled Admin is not somebody who can administer, so it does not count as the other one. */
  @Test
  void GivenASecondAdminWhoIsDisabled_WhenTheActiveOneIsDemoted_ThenItIsRefused() {
    Account active = existing(acme, Role.ADMIN, true);
    existing(acme, Role.ADMIN, false);

    assertThatThrownBy(
            () ->
                service.changeMembership(
                    admin(acme),
                    new ChangeMembershipCommand(
                        acme, active.id(), Role.SCHEDULER, Optional.empty())))
        .isInstanceOf(LastActiveAdminException.class);
  }

  /** An Admin of another organisation does not count either, however many there are. */
  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenOursIsDemoted_ThenItIsRefused() {
    Account ours = existing(acme, Role.ADMIN, true);
    existing(rival, Role.ADMIN, true);

    assertThatThrownBy(
            () ->
                service.changeMembership(
                    admin(acme),
                    new ChangeMembershipCommand(acme, ours.id(), Role.SCHEDULER, Optional.empty())))
        .isInstanceOf(LastActiveAdminException.class);
  }

  @Test
  void GivenAnAccountOfAnotherOrganisation_WhenChangingIt_ThenItIsNotFound() {
    Account theirs = existing(rival, Role.EMPLOYEE, true);

    assertThatThrownBy(
            () ->
                service.changeMembership(
                    admin(acme),
                    new ChangeMembershipCommand(acme, theirs.id(), Role.ADMIN, Optional.empty())))
        .isInstanceOf(UnknownAccountException.class);
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenLinkingOurAccountToThem_ThenItIsRefused() {
    existing(acme, Role.ADMIN, true);
    Account account = existing(acme, Role.EMPLOYEE, true);
    EmployeeId theirs = employees.employedBy(rival);

    assertThatThrownBy(
            () ->
                service.changeMembership(
                    admin(acme),
                    new ChangeMembershipCommand(
                        acme, account.id(), Role.EMPLOYEE, Optional.of(theirs))))
        .isInstanceOf(NotAnEmployeeHereException.class);
  }

  /**
   * Who is acting is resolved from the stored account on every request (ADR-011), so a demotion is
   * already in force on the next one. Ending the sessions would sign somebody out for being given a
   * different job.
   */
  @Test
  void GivenARoleChange_WhenMade_ThenNoSessionIsEnded() {
    existing(acme, Role.ADMIN, true);
    Account account = existing(acme, Role.ADMIN, true);

    service.changeMembership(
        admin(acme),
        new ChangeMembershipCommand(acme, account.id(), Role.SCHEDULER, Optional.empty()));

    assertThat(sessions.everySessionEndedFor(account.id())).isEmpty();
  }

  @Test
  void GivenARoleChange_WhenMade_ThenTheAdministrationLockIsTakenFirst() {
    existing(acme, Role.ADMIN, true);
    Account account = existing(acme, Role.ADMIN, true);

    service.changeMembership(
        admin(acme),
        new ChangeMembershipCommand(acme, account.id(), Role.SCHEDULER, Optional.empty()));

    assertThat(accounts.lockedAdministrations()).containsExactly(acme);
  }

  // Disabling and restoring

  @Test
  void GivenAnAccount_WhenDisabled_ThenItMayNoLongerAct() {
    TenantActor admin = admin(acme);
    existing(acme, Role.ADMIN, true);
    Account account = existing(acme, Role.EMPLOYEE, true);

    service.setAccountActive(admin, new SetAccountActiveCommand(acme, account.id(), false));

    assertThat(accounts.find(acme, account.id()).orElseThrow().active()).isFalse();
  }

  /** A token already issued would otherwise keep working until it expired. */
  @Test
  void GivenAnAccount_WhenDisabled_ThenEverySessionOfItEnds() {
    existing(acme, Role.ADMIN, true);
    Account account = existing(acme, Role.EMPLOYEE, true);

    service.setAccountActive(admin(acme), new SetAccountActiveCommand(acme, account.id(), false));

    assertThat(sessions.everySessionEndedFor(account.id()))
        .contains(SessionRevocation.ACCOUNT_DISABLED);
  }

  @Test
  void GivenTheLastActiveAdmin_WhenDisabled_ThenItIsRefused() {
    Account only = existing(acme, Role.ADMIN, true);

    assertThatThrownBy(
            () ->
                service.setAccountActive(
                    admin(acme), new SetAccountActiveCommand(acme, only.id(), false)))
        .isInstanceOf(LastActiveAdminException.class);

    assertThat(accounts.find(acme, only.id()).orElseThrow().active()).isTrue();
    assertThat(sessions.everySessionEndedFor(only.id())).isEmpty();
  }

  @Test
  void GivenADisabledAccount_WhenRestored_ThenItMayActAgain() {
    existing(acme, Role.ADMIN, true);
    Account account = existing(acme, Role.EMPLOYEE, false);

    service.setAccountActive(admin(acme), new SetAccountActiveCommand(acme, account.id(), true));

    assertThat(accounts.find(acme, account.id()).orElseThrow().active()).isTrue();
  }

  @Test
  void GivenAnAccount_WhenRestored_ThenNoSessionIsEnded() {
    existing(acme, Role.ADMIN, true);
    Account account = existing(acme, Role.EMPLOYEE, false);

    service.setAccountActive(admin(acme), new SetAccountActiveCommand(acme, account.id(), true));

    assertThat(sessions.everySessionEndedFor(account.id())).isEmpty();
  }

  @Test
  void GivenADisabling_WhenMade_ThenTheAdministrationLockIsTakenFirst() {
    existing(acme, Role.ADMIN, true);
    Account account = existing(acme, Role.EMPLOYEE, true);

    service.setAccountActive(admin(acme), new SetAccountActiveCommand(acme, account.id(), false));

    assertThat(accounts.lockedAdministrations()).containsExactly(acme);
  }

  @Test
  void GivenAnAccountOfAnotherOrganisation_WhenDisablingIt_ThenItIsNotFound() {
    Account theirs = existing(rival, Role.EMPLOYEE, true);

    assertThatThrownBy(
            () ->
                service.setAccountActive(
                    admin(acme), new SetAccountActiveCommand(acme, theirs.id(), false)))
        .isInstanceOf(UnknownAccountException.class);
  }

  // Resetting a password

  @Test
  void GivenAnAccount_WhenItsPasswordIsReset_ThenItHoldsTheNewOneAndMustReplaceIt() {
    Account account = existing(acme, Role.EMPLOYEE, true);
    String replacement = "another invented password";

    service.resetPassword(admin(acme), new ResetPasswordCommand(acme, account.id(), replacement));

    Account reset = accounts.find(acme, account.id()).orElseThrow();
    assertThat(reset.passwordHash()).isEqualTo(passwords.hash(replacement));
    assertThat(reset.passwordChangeRequired()).isTrue();
  }

  /** Whoever knew the old password may still hold a session opened with it. */
  @Test
  void GivenAnAccount_WhenItsPasswordIsReset_ThenEverySessionOfItEnds() {
    Account account = existing(acme, Role.EMPLOYEE, true);

    service.resetPassword(admin(acme), new ResetPasswordCommand(acme, account.id(), A_PASSWORD));

    assertThat(sessions.everySessionEndedFor(account.id()))
        .contains(SessionRevocation.PASSWORD_RESET);
  }

  /** A reset that left the lock running would be useless for as long as the lock had left. */
  @Test
  void GivenALockedAccount_WhenItsPasswordIsReset_ThenItIsNoLongerLocked() {
    Account account = existing(acme, Role.EMPLOYEE, true);
    accounts.save(account.recordFailedSignIn(NOW, LOCK_ON_THE_FIRST_FAILURE));

    service.resetPassword(admin(acme), new ResetPasswordCommand(acme, account.id(), A_PASSWORD));

    Account reset = accounts.find(acme, account.id()).orElseThrow();
    assertThat(reset.isLockedAt(NOW)).isFalse();
    assertThat(reset.failedSignIns()).isZero();
  }

  @Test
  void GivenATemporaryPasswordBelowThePolicy_WhenResetting_ThenNothingChanges() {
    Account account = existing(acme, Role.EMPLOYEE, true);

    assertThatThrownBy(
            () ->
                service.resetPassword(
                    admin(acme), new ResetPasswordCommand(acme, account.id(), TOO_SHORT)))
        .isInstanceOf(UnacceptablePasswordException.class)
        .hasMessageNotContaining(TOO_SHORT);

    assertThat(accounts.find(acme, account.id()).orElseThrow().passwordHash())
        .isEqualTo(passwords.hash(A_PASSWORD));
    assertThat(sessions.everySessionEndedFor(account.id())).isEmpty();
  }

  @Test
  void GivenAnAccountOfAnotherOrganisation_WhenResettingItsPassword_ThenItIsNotFound() {
    Account theirs = existing(rival, Role.EMPLOYEE, true);

    assertThatThrownBy(
            () ->
                service.resetPassword(
                    admin(acme), new ResetPasswordCommand(acme, theirs.id(), A_PASSWORD)))
        .isInstanceOf(UnknownAccountException.class);
    assertThat(sessions.everySessionEndedFor(theirs.id())).isEmpty();
  }

  @Test
  void GivenASchedulerRole_WhenResettingSomebodysPassword_ThenItIsRefused() {
    Account account = existing(acme, Role.EMPLOYEE, true);

    assertThatThrownBy(
            () ->
                service.resetPassword(
                    actor(acme, Role.SCHEDULER),
                    new ResetPasswordCommand(acme, account.id(), A_PASSWORD)))
        .isInstanceOf(NotPermittedException.class);
  }

  /**
   * Changing your own password takes the current one (F14). An Admin able to reset themselves here
   * would hold that protection and a way around it in the same session, so this door is closed to
   * them — and closing it costs nothing, because the other one is open to anybody signed in.
   */
  @Test
  void GivenAnAdmin_WhenResettingTheirOwnPassword_ThenItIsRefused() {
    Account theirs = existing(acme, Role.ADMIN, true);
    TenantActor themself = new TenantActor(theirs.id(), acme, Role.ADMIN);

    assertThatThrownBy(
            () ->
                service.resetPassword(
                    themself, new ResetPasswordCommand(acme, theirs.id(), A_PASSWORD)))
        .isInstanceOf(CannotResetOwnPasswordException.class);

    assertThat(accounts.find(acme, theirs.id()).orElseThrow().passwordHash())
        .isEqualTo(passwords.hash(A_PASSWORD));
    assertThat(sessions.everySessionEndedFor(theirs.id())).isEmpty();
    assertThat(audit.changes()).isEmpty();
  }

  /** A colleague's is the whole point, including another Admin's. */
  @Test
  void GivenTwoAdmins_WhenOneResetsTheOther_ThenItIsAllowed() {
    Account other = existing(acme, Role.ADMIN, true);
    TenantActor themself = new TenantActor(UserId.newId(), acme, Role.ADMIN);
    String replacement = "another invented password";

    service.resetPassword(themself, new ResetPasswordCommand(acme, other.id(), replacement));

    assertThat(accounts.find(acme, other.id()).orElseThrow().passwordHash())
        .isEqualTo(passwords.hash(replacement));
  }

  // An Employee account is always linked to an employee (R34)

  @Test
  void GivenAnAdmin_WhenCreatingAnEmployeeAccountLinkedToNobody_ThenNothingIsCreatedOrRecorded() {
    TenantActor admin = admin(acme);

    assertThatThrownBy(() -> service.createAccount(admin, creating(acme, Role.EMPLOYEE)))
        .isInstanceOf(UnlinkedEmployeeAccountException.class);

    assertThat(service.findAccounts(admin, acme)).isEmpty();
    assertThat(audit.changes()).isEmpty();
  }

  @Test
  void GivenAnAdmin_WhenCreatingAnEmployeeAccountLinkedToSomebodyHere_ThenItIsCreated() {
    TenantActor admin = admin(acme);
    EmployeeId ada = employees.employedBy(acme);

    UserId created =
        service.createAccount(
            admin,
            new CreateAccountCommand(
                acme, anInventedSignInName(), A_PASSWORD, Role.EMPLOYEE, Optional.of(ada)));

    assertThat(accounts.find(acme, created).orElseThrow().membership())
        .isEqualTo(new TenantMembership(acme, Role.EMPLOYEE, Optional.of(ada)));
  }

  @Test
  void GivenAnEmployeeAccount_WhenUnlinked_ThenItIsRefusedAndKeepsItsLink() {
    existing(acme, Role.ADMIN, true);
    Account employee = existing(acme, Role.EMPLOYEE, true);

    assertThatThrownBy(
            () ->
                service.changeMembership(
                    admin(acme),
                    new ChangeMembershipCommand(
                        acme, employee.id(), Role.EMPLOYEE, Optional.empty())))
        .isInstanceOf(UnlinkedEmployeeAccountException.class);

    assertThat(accounts.find(acme, employee.id()).orElseThrow().membership())
        .isEqualTo(employee.membership());
    assertThat(audit.changes()).isEmpty();
  }

  @Test
  void GivenAnAccountLinkedToNobody_WhenMadeAnEmployeeWithoutALink_ThenItIsRefused() {
    existing(acme, Role.ADMIN, true);
    Account scheduler = existing(acme, Role.SCHEDULER, true);

    assertThatThrownBy(
            () ->
                service.changeMembership(
                    admin(acme),
                    new ChangeMembershipCommand(
                        acme, scheduler.id(), Role.EMPLOYEE, Optional.empty())))
        .isInstanceOf(UnlinkedEmployeeAccountException.class);

    assertThat(accounts.find(acme, scheduler.id()).orElseThrow().membership())
        .isEqualTo(new TenantMembership(acme, Role.SCHEDULER, Optional.empty()));
  }

  /**
   * Both rules would refuse this, and the last Admin's answers: it is checked first, and it is the
   * one whose breach nobody could repair.
   */
  @Test
  void GivenTheLastActiveAdmin_WhenMadeAnEmployeeLinkedToNobody_ThenTheLastAdminRuleAnswers() {
    Account only = existing(acme, Role.ADMIN, true);

    assertThatThrownBy(
            () ->
                service.changeMembership(
                    admin(acme),
                    new ChangeMembershipCommand(acme, only.id(), Role.EMPLOYEE, Optional.empty())))
        .isInstanceOf(LastActiveAdminException.class);
  }

  @Test
  void GivenAnEmployeeAccountLinkedToNobody_WhenRestored_ThenItIsRefusedAndStaysDisabled() {
    Account migrated = migrated(acme);

    assertThatThrownBy(
            () ->
                service.setAccountActive(
                    admin(acme), new SetAccountActiveCommand(acme, migrated.id(), true)))
        .isInstanceOf(UnlinkedEmployeeAccountException.class);

    assertThat(accounts.find(acme, migrated.id()).orElseThrow().active()).isFalse();
    assertThat(audit.changes()).isEmpty();
  }

  /** Linking is not restoring: the Admin restores it as an act of its own, and it is recorded. */
  @Test
  void GivenAnEmployeeAccountLinkedToNobody_WhenLinked_ThenItStaysDisabledUntilRestored() {
    TenantActor admin = admin(acme);
    Account migrated = migrated(acme);
    EmployeeId ada = employees.employedBy(acme);

    service.changeMembership(
        admin, new ChangeMembershipCommand(acme, migrated.id(), Role.EMPLOYEE, Optional.of(ada)));

    assertThat(accounts.find(acme, migrated.id()).orElseThrow().active()).isFalse();

    service.setAccountActive(admin, new SetAccountActiveCommand(acme, migrated.id(), true));

    assertThat(accounts.find(acme, migrated.id()).orElseThrow().active()).isTrue();
    assertThat(audit.changes())
        .containsExactly(AccessChange.ACCOUNT_MEMBERSHIP_CHANGED, AccessChange.ACCOUNT_ENABLED);
  }

  /** R34 is about the Employee role alone, so a Scheduler may be nobody's and may be restored. */
  @Test
  void GivenAnEmployeeAccountLinkedToNobody_WhenMadeAScheduler_ThenItMayBeRestored() {
    TenantActor admin = admin(acme);
    Account migrated = migrated(acme);

    service.changeMembership(
        admin, new ChangeMembershipCommand(acme, migrated.id(), Role.SCHEDULER, Optional.empty()));
    service.setAccountActive(admin, new SetAccountActiveCommand(acme, migrated.id(), true));

    Account restored = accounts.find(acme, migrated.id()).orElseThrow();
    assertThat(restored.active()).isTrue();
    assertThat(restored.membership())
        .isEqualTo(new TenantMembership(acme, Role.SCHEDULER, Optional.empty()));
  }

  // What is recorded

  @Test
  void GivenAnAccountCreated_WhenRecorded_ThenItIsAgainstTheAdminAndTheOrganisation() {
    TenantActor admin = admin(acme);

    UserId created = service.createAccount(admin, creating(acme, Role.SCHEDULER));

    assertThat(audit.of(AccessChange.ACCOUNT_CREATED))
        .containsExactly(
            new AuditedChange(
                Optional.of(acme), admin.id(), AccessChange.ACCOUNT_CREATED, created.value()));
  }

  @Test
  void GivenEachKindOfChange_WhenMade_ThenEachIsRecordedAsItself() {
    TenantActor admin = admin(acme);
    existing(acme, Role.ADMIN, true);
    Account account = existing(acme, Role.EMPLOYEE, true);

    service.changeMembership(
        admin, new ChangeMembershipCommand(acme, account.id(), Role.SCHEDULER, Optional.empty()));
    service.setAccountActive(admin, new SetAccountActiveCommand(acme, account.id(), false));
    service.setAccountActive(admin, new SetAccountActiveCommand(acme, account.id(), true));
    service.resetPassword(admin, new ResetPasswordCommand(acme, account.id(), A_PASSWORD));

    assertThat(audit.changes())
        .containsExactly(
            AccessChange.ACCOUNT_MEMBERSHIP_CHANGED,
            AccessChange.ACCOUNT_DISABLED,
            AccessChange.ACCOUNT_ENABLED,
            AccessChange.ACCOUNT_PASSWORD_RESET);
  }

  @Test
  void GivenAccounts_WhenMerelyListed_ThenNothingIsRecorded() {
    existing(acme, Role.ADMIN, true);

    service.findAccounts(admin(acme), acme);

    assertThat(audit.changes()).isEmpty();
  }

  @Test
  void GivenARefusedChange_WhenTheLastAdminWouldGo_ThenNothingIsRecorded() {
    Account only = existing(acme, Role.ADMIN, true);

    assertThatThrownBy(
            () ->
                service.setAccountActive(
                    admin(acme), new SetAccountActiveCommand(acme, only.id(), false)))
        .isInstanceOf(LastActiveAdminException.class);

    assertThat(audit.changes()).isEmpty();
  }
}
