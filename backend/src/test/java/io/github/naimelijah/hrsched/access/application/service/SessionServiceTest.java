package io.github.naimelijah.hrsched.access.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.access.application.port.in.AccountKind;
import io.github.naimelijah.hrsched.access.application.port.in.ChangePasswordCommand;
import io.github.naimelijah.hrsched.access.application.port.in.ChangePasswordOutcome;
import io.github.naimelijah.hrsched.access.application.port.in.RefreshOutcome;
import io.github.naimelijah.hrsched.access.application.port.in.RefreshRefusal;
import io.github.naimelijah.hrsched.access.application.port.in.SignInCommand;
import io.github.naimelijah.hrsched.access.application.port.in.SignInOutcome;
import io.github.naimelijah.hrsched.access.application.port.in.SignInRefusal;
import io.github.naimelijah.hrsched.access.application.port.out.AccessChange;
import io.github.naimelijah.hrsched.access.application.port.out.AccessTokenIssuer;
import io.github.naimelijah.hrsched.access.application.port.out.AuthenticatedSession;
import io.github.naimelijah.hrsched.access.application.port.out.IssuedAccessToken;
import io.github.naimelijah.hrsched.access.application.port.out.RefreshTokenSecret;
import io.github.naimelijah.hrsched.access.application.port.out.SessionRepository;
import io.github.naimelijah.hrsched.access.application.port.out.SessionSecrets;
import io.github.naimelijah.hrsched.access.application.port.out.StoredRefreshToken;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.LockoutPolicy;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.PasswordPolicy;
import io.github.naimelijah.hrsched.access.domain.model.SessionPolicy;
import io.github.naimelijah.hrsched.access.domain.model.SessionRevocation;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.SignInSession;
import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.Operator;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Specification of signing in, driven without Spring.
 *
 * <p>Three of these tests are about what a refusal must <em>not</em> reveal, and they are the ones
 * whose failure would be a security incident rather than a bug: that a password is compared even
 * when no account was found, that a locked account is not told apart from a wrong password, and
 * that a failed attempt survives the refusal that follows it.
 *
 * <p>The comparison count is asserted rather than the time taken. Timing would be a test about the
 * machine it ran on; counting is the same property stated in a way that cannot flake.
 *
 * <p>Every password, name and policy number here is invented for the test.
 */
class SessionServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-20T09:00:00Z");

  /**
   * Long enough before {@link #NOW} for any lock {@link #LOCK_AFTER_THREE} sets to have run out.
   */
  private static final Instant AN_HOUR_AGO = NOW.minus(Duration.ofHours(1));

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final String ADA = "ada@example.com";
  private static final String RIGHT_PASSWORD = "an invented password for a test";
  private static final String WRONG_PASSWORD = "not the invented password";
  private static final LockoutPolicy LOCK_AFTER_THREE =
      new LockoutPolicy(3, Duration.ofMinutes(15));
  private static final SessionPolicy A_DAY_AND_AN_HOUR =
      new SessionPolicy(Duration.ofHours(12), Duration.ofHours(1));
  private static final PasswordPolicy A_FIFTEEN_CHARACTER_MINIMUM = new PasswordPolicy(15, 72);

  private final StubAccountRepository accounts = new StubAccountRepository();
  private final StubSessionRepository sessions = new StubSessionRepository();
  private final StubPasswordHasher passwords = new StubPasswordHasher();
  private final StubAccessTokenIssuer tokens = new StubAccessTokenIssuer();
  private final StubSessionSecrets secrets = new StubSessionSecrets();
  private final RecordingAuditTrail audit = new RecordingAuditTrail();

  private final SessionService service = serviceAt(NOW, A_DAY_AND_AN_HOUR);

  private SessionService serviceAt(Instant now, SessionPolicy policy) {
    return new SessionService(
        accounts,
        sessions,
        passwords,
        tokens,
        secrets,
        audit,
        Clock.fixed(now, ZoneOffset.UTC),
        LOCK_AFTER_THREE,
        policy,
        A_FIFTEEN_CHARACTER_MINIMUM);
  }

  @Test
  void GivenTheRightPassword_WhenSigningIn_ThenASessionBeginsAndIsPublished() {
    EmployeeId ada = EmployeeId.newId();
    Account account = accounts.given(tenant(ADA, Role.SCHEDULER, Optional.of(ada)));

    SignInOutcome outcome = service.signIn(new SignInCommand(ADA, RIGHT_PASSWORD));

    assertThat(outcome)
        .isInstanceOfSatisfying(
            SignInOutcome.SignedIn.class,
            signedIn -> {
              assertThat(signedIn.session().session().userId()).isEqualTo(account.id().value());
              assertThat(signedIn.session().session().kind()).isEqualTo(AccountKind.TENANT);
              assertThat(signedIn.session().session().organisation()).contains(ACME.value());
              assertThat(signedIn.session().session().role()).contains(Role.SCHEDULER);
              assertThat(signedIn.session().session().employee()).contains(ada.value());
              assertThat(signedIn.session().accessToken()).isNotBlank();
              assertThat(signedIn.session().refreshToken()).isNotBlank();
            });
    assertThat(sessions.started()).hasSize(1);
  }

  @Test
  void GivenAnOperatorAccount_WhenSigningIn_ThenTheSessionCarriesNoOrganisationOrRole() {
    accounts.given(operator("operator@example.com"));

    SignInOutcome outcome =
        service.signIn(new SignInCommand("operator@example.com", RIGHT_PASSWORD));

    assertThat(outcome)
        .isInstanceOfSatisfying(
            SignInOutcome.SignedIn.class,
            signedIn -> {
              assertThat(signedIn.session().session().kind()).isEqualTo(AccountKind.OPERATOR);
              assertThat(signedIn.session().session().organisation()).isEmpty();
              assertThat(signedIn.session().session().role()).isEmpty();
            });
  }

  /**
   * The comparison has to happen even when there is nothing to compare against, or refusing an
   * unknown name would be measurably quicker than refusing a known one - which is a way to find out
   * who has an account here.
   */
  @Test
  void GivenAnUnknownSignInName_WhenSigningIn_ThenAPasswordIsStillCompared() {
    SignInOutcome outcome = service.signIn(new SignInCommand("nobody@example.com", RIGHT_PASSWORD));

    assertThat(passwords.comparisons()).isEqualTo(1);
    assertThat(outcome)
        .isInstanceOfSatisfying(
            SignInOutcome.Refused.class,
            refused -> {
              assertThat(refused.reason()).isEqualTo(SignInRefusal.UNKNOWN_ACCOUNT);
              assertThat(refused.account()).isEmpty();
            });
  }

  /** A name no account could ever hold is refused exactly as a name nobody holds. */
  @Test
  void GivenAnUnusableSignInName_WhenSigningIn_ThenItIsRefusedAsAnUnknownAccount() {
    SignInOutcome outcome = service.signIn(new SignInCommand("   ", RIGHT_PASSWORD));

    assertThat(outcome)
        .isInstanceOfSatisfying(
            SignInOutcome.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(SignInRefusal.UNKNOWN_ACCOUNT));
    assertThat(passwords.comparisons()).isEqualTo(1);
  }

  @Test
  void GivenALockedAccount_WhenSigningIn_ThenAPasswordIsStillComparedAndNothingChanges() {
    Account locked =
        accounts.given(
            tenant(ADA, Role.SCHEDULER, Optional.empty())
                .recordFailedSignIn(NOW, LOCK_AFTER_THREE)
                .recordFailedSignIn(NOW, LOCK_AFTER_THREE)
                .recordFailedSignIn(NOW, LOCK_AFTER_THREE));

    SignInOutcome outcome = service.signIn(new SignInCommand(ADA, RIGHT_PASSWORD));

    assertThat(passwords.comparisons()).isEqualTo(1);
    assertThat(outcome)
        .isInstanceOfSatisfying(
            SignInOutcome.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(SignInRefusal.LOCKED));
    Account after = accounts.findBySignInName(new SignInName(ADA)).orElseThrow();
    assertThat(after.failedSignIns())
        .describedAs(
            "a lock that extended itself on every attempt would be a way to keep somebody out")
        .isEqualTo(locked.failedSignIns());
    assertThat(after.lockedUntil()).isEqualTo(locked.lockedUntil());
  }

  /** What V9 left of an Employee account stored linked to nobody: it cannot sign in. */
  @Test
  void GivenAnEmployeeAccountLinkedToNobody_WhenSigningInWithTheRightPassword_ThenItIsRefused() {
    accounts.given(
        new Account(
            UserId.newId(),
            new SignInName(ADA),
            passwords.hash(RIGHT_PASSWORD),
            new TenantMembership(ACME, Role.EMPLOYEE, Optional.empty()),
            false,
            false,
            0,
            Optional.empty(),
            NOW.minus(Duration.ofDays(1))));

    SignInOutcome outcome = service.signIn(new SignInCommand(ADA, RIGHT_PASSWORD));

    assertThat(outcome)
        .isInstanceOfSatisfying(
            SignInOutcome.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(SignInRefusal.DISABLED));
    assertThat(sessions.started()).isEmpty();
  }

  @Test
  void GivenADisabledAccount_WhenSigningInWithTheRightPassword_ThenItIsRefused() {
    accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()).disabled());

    SignInOutcome outcome = service.signIn(new SignInCommand(ADA, RIGHT_PASSWORD));

    assertThat(outcome)
        .isInstanceOfSatisfying(
            SignInOutcome.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(SignInRefusal.DISABLED));
    assertThat(sessions.started()).isEmpty();
  }

  @Test
  void GivenAWrongPassword_WhenRefused_ThenTheFailureIsCounted() {
    accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()));

    service.signIn(new SignInCommand(ADA, WRONG_PASSWORD));

    assertThat(accounts.findBySignInName(new SignInName(ADA)).orElseThrow().failedSignIns())
        .isEqualTo(1);
  }

  @Test
  void GivenTheLockingFailure_WhenRecorded_ThenAnAccountLockedEntryIsAudited() {
    accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()));

    service.signIn(new SignInCommand(ADA, WRONG_PASSWORD));
    service.signIn(new SignInCommand(ADA, WRONG_PASSWORD));
    assertThat(audit.changes()).doesNotContain(AccessChange.ACCOUNT_LOCKED);

    service.signIn(new SignInCommand(ADA, WRONG_PASSWORD));

    assertThat(audit.of(AccessChange.ACCOUNT_LOCKED)).hasSize(1);
  }

  @Test
  void GivenEarlierFailures_WhenSigningInSuccessfully_ThenTheCountResets() {
    accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()));
    service.signIn(new SignInCommand(ADA, WRONG_PASSWORD));

    service.signIn(new SignInCommand(ADA, RIGHT_PASSWORD));

    assertThat(accounts.findBySignInName(new SignInName(ADA)).orElseThrow().failedSignIns())
        .isZero();
  }

  @Test
  void
      GivenASignIn_WhenItSucceeds_ThenTheRefreshTokenExpiresAtTheIdleLimitOrTheSessionEndWhicheverIsSooner() {
    accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()));

    SignInOutcome outcome = service.signIn(new SignInCommand(ADA, RIGHT_PASSWORD));

    assertThat(outcome)
        .isInstanceOfSatisfying(
            SignInOutcome.SignedIn.class,
            signedIn ->
                assertThat(signedIn.session().refreshTokenExpiresAt())
                    .isEqualTo(NOW.plus(Duration.ofHours(1))));
  }

  /**
   * A session whose whole life is shorter than the idle limit ends when it ends. Renewing must
   * never be able to carry a token past the end the session was given when it began.
   */
  @Test
  void GivenASessionShorterThanTheIdleLimit_WhenSigningIn_ThenTheRefreshTokenEndsWithTheSession() {
    accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()));
    SessionService shortLived =
        serviceAt(NOW, new SessionPolicy(Duration.ofMinutes(30), Duration.ofMinutes(30)));

    SignInOutcome outcome = shortLived.signIn(new SignInCommand(ADA, RIGHT_PASSWORD));

    assertThat(outcome)
        .isInstanceOfSatisfying(
            SignInOutcome.SignedIn.class,
            signedIn ->
                assertThat(signedIn.session().refreshTokenExpiresAt())
                    .isEqualTo(NOW.plus(Duration.ofMinutes(30))));
  }

  @Test
  void GivenASignIn_WhenItSucceeds_ThenTheAuditedSubjectIsTheSessionRatherThanTheAccount() {
    Account account = accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()));

    service.signIn(new SignInCommand(ADA, RIGHT_PASSWORD));

    assertThat(audit.of(AccessChange.SIGNED_IN)).hasSize(1);
    assertThat(audit.of(AccessChange.SIGNED_IN).getFirst().subject())
        .isNotEqualTo(account.id().value());
    assertThat(audit.of(AccessChange.SIGNED_IN).getFirst().organisation()).contains(ACME);
  }

  @Test
  void GivenAMixedCaseSignInName_WhenSigningIn_ThenItMatches() {
    accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()));

    assertThat(service.signIn(new SignInCommand("  ADA@Example.COM ", RIGHT_PASSWORD)))
        .isInstanceOf(SignInOutcome.SignedIn.class);
  }

  /**
   * The race the conditional update exists for. Two refreshes arrive holding one token, both read
   * it as current, and the database awards the exchange to one of them; the other is told it
   * changed no row. There is no way to tell that from somebody presenting a token that had already
   * been spent, so it is treated as exactly that.
   */
  @Test
  void GivenTwoRefreshesRacingWithOneToken_WhenTheSecondFindsItReplaced_ThenItIsTreatedAsReuse() {
    Account account = accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()));
    SignInSession session =
        new SignInSession(
            SignInSessionId.newId(), account.id(), NOW, NOW.plus(Duration.ofHours(12)));
    sessions.given(
        "a-current-token",
        new StoredRefreshToken(session, NOW.plus(Duration.ofHours(1)), Optional.empty()));
    sessions.whereTheExchangeIsAlreadyTaken();

    RefreshOutcome outcome = service.refresh("a-current-token");

    assertThat(outcome)
        .isInstanceOfSatisfying(
            RefreshOutcome.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(RefreshRefusal.REUSED));
    assertThat(sessions.revocationOf(session.id())).contains(SessionRevocation.REFRESH_REUSED);
    assertThat(audit.of(AccessChange.REFRESH_TOKEN_REUSED)).hasSize(1);
  }

  @Test
  void GivenARefreshTokenAlreadyReplaced_WhenPresented_ThenTheWholeSessionIsRevoked() {
    Account account = accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()));
    SignInSession session =
        new SignInSession(
            SignInSessionId.newId(), account.id(), NOW, NOW.plus(Duration.ofHours(12)));
    sessions.given(
        "a-spent-token",
        new StoredRefreshToken(
            session, NOW.plus(Duration.ofHours(1)), Optional.of(NOW.minusSeconds(60))));

    service.refresh("a-spent-token");

    assertThat(sessions.revocationOf(session.id())).contains(SessionRevocation.REFRESH_REUSED);
  }

  @Test
  void GivenAnUnknownRefreshToken_WhenRefreshing_ThenNothingIsRevoked() {
    RefreshOutcome outcome = service.refresh("a-token-nobody-ever-issued");

    assertThat(outcome)
        .isInstanceOfSatisfying(
            RefreshOutcome.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(RefreshRefusal.UNKNOWN_TOKEN));
    assertThat(audit.changes()).isEmpty();
  }

  // --- #33: changing one's own password holds the account from its read (ADR-041) ---------------
  //
  // A password change stores the account whole, so a change made to a copy read before an Admin's
  // disable committed would enable the account again. It reads the account for update instead,
  // scoped to the caller's own organisation, or to none for the operator. That the read really
  // waits is proven against PostgreSQL, in JdbcAccountRepositoryTest.

  @Test
  void GivenAPasswordChange_WhenMade_ThenTheAccountIsReadForUpdate() {
    Account account = accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()));
    TenantActor themself = new TenantActor(account.id(), ACME, Role.SCHEDULER);

    ChangePasswordOutcome outcome =
        service.changePassword(
            themself,
            new ChangePasswordCommand(RIGHT_PASSWORD, "another invented password for a test"));

    assertThat(outcome).isInstanceOf(ChangePasswordOutcome.Changed.class);
    assertThat(accounts.heldForUpdate()).containsExactly(account.id());
  }

  /** The operator belongs to no organisation, so its account is held where it is: in none. */
  @Test
  void GivenTheOperatorChangingTheirPassword_WhenMade_ThenTheirAccountIsReadForUpdate() {
    Account account = accounts.given(operator("operator@example.com"));

    ChangePasswordOutcome outcome =
        service.changePassword(
            new Operator(account.id()),
            new ChangePasswordCommand(RIGHT_PASSWORD, "another invented password for a test"));

    assertThat(outcome).isInstanceOf(ChangePasswordOutcome.Changed.class);
    assertThat(accounts.heldForUpdate()).containsExactly(account.id());
  }

  // --- R29: changing one's own password while locked -------------------------------------------
  //
  // Refused as sign-in refuses a locked account: whatever password is given, nothing counted, and
  // the comparison still made, so how long the refusal takes does not say the account is locked.

  @Test
  void GivenALockedAccount_WhenChangingItsPassword_ThenAPasswordIsStillComparedAndNothingChanges() {
    Account locked =
        accounts.given(
            tenant(ADA, Role.SCHEDULER, Optional.empty())
                .recordFailedSignIn(NOW, LOCK_AFTER_THREE)
                .recordFailedSignIn(NOW, LOCK_AFTER_THREE)
                .recordFailedSignIn(NOW, LOCK_AFTER_THREE));
    TenantActor themself = new TenantActor(locked.id(), ACME, Role.SCHEDULER);

    ChangePasswordOutcome outcome =
        service.changePassword(
            themself,
            new ChangePasswordCommand(RIGHT_PASSWORD, "another invented password for a test"));

    assertThat(passwords.comparisons())
        .describedAs("compared before the lock is consulted, as at sign-in")
        .isEqualTo(1);
    assertThat(outcome).isInstanceOf(ChangePasswordOutcome.CurrentPasswordRejected.class);
    Account after = accounts.findBySignInName(new SignInName(ADA)).orElseThrow();
    assertThat(after.passwordHash()).isEqualTo(locked.passwordHash());
    assertThat(after.failedSignIns())
        .describedAs(
            "a lock that extended itself on every attempt would be a way to keep somebody out")
        .isEqualTo(locked.failedSignIns());
    assertThat(after.lockedUntil()).isEqualTo(locked.lockedUntil());
    assertThat(audit.changes()).isEmpty();
  }

  /** A lock that has run out bars nothing: it was a pause on guessing, not a disable. */
  @Test
  void GivenALockThatHasRunOut_WhenChangingThePasswordWithTheRightCurrentOne_ThenItIsChanged() {
    Account once =
        accounts.given(
            tenant(ADA, Role.SCHEDULER, Optional.empty())
                .recordFailedSignIn(AN_HOUR_AGO, LOCK_AFTER_THREE)
                .recordFailedSignIn(AN_HOUR_AGO, LOCK_AFTER_THREE)
                .recordFailedSignIn(AN_HOUR_AGO, LOCK_AFTER_THREE));
    assertThat(once.lockedUntil()).describedAs("precondition: it was locked").isPresent();
    assertThat(once.isLockedAt(NOW)).describedAs("precondition: the lock has run out").isFalse();
    TenantActor themself = new TenantActor(once.id(), ACME, Role.SCHEDULER);

    ChangePasswordOutcome outcome =
        service.changePassword(
            themself,
            new ChangePasswordCommand(RIGHT_PASSWORD, "another invented password for a test"));

    assertThat(outcome).isInstanceOf(ChangePasswordOutcome.Changed.class);
  }

  /** The instant a lock ends is the first instant it no longer holds. */
  @Test
  void GivenALockEndingThisInstant_WhenChangingThePasswordWithTheRightCurrentOne_ThenItIsChanged() {
    Instant lockedAt = NOW.minus(LOCK_AFTER_THREE.duration());
    Account once =
        accounts.given(
            tenant(ADA, Role.SCHEDULER, Optional.empty())
                .recordFailedSignIn(lockedAt, LOCK_AFTER_THREE)
                .recordFailedSignIn(lockedAt, LOCK_AFTER_THREE)
                .recordFailedSignIn(lockedAt, LOCK_AFTER_THREE));
    assertThat(once.lockedUntil()).describedAs("precondition: it ends now").contains(NOW);
    TenantActor themself = new TenantActor(once.id(), ACME, Role.SCHEDULER);

    ChangePasswordOutcome outcome =
        service.changePassword(
            themself,
            new ChangePasswordCommand(RIGHT_PASSWORD, "another invented password for a test"));

    assertThat(outcome).isInstanceOf(ChangePasswordOutcome.Changed.class);
  }

  // --- #33: sign-in decides on the account as it is once held (ADR-041)
  // ---------------------------
  //
  // Sign-in reads the account, then compares the password, which is slow on purpose. A change
  // committed in between - an Admin's disable or reset, another attempt's failure - must not be
  // stored over by this attempt, nor ignored by it. Each test below makes the unlocked read return
  // the account as it was before such a change, and stores the account as it is after.

  @Test
  void
      GivenTheAccountDisabledWhileAFailingAttemptWasChecked_WhenTheFailureIsCounted_ThenTheAccountStaysDisabled() {
    Account before = accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()));
    accounts.signInReadsBeforeAChange(before);
    accounts.save(before.disabled());

    SignInOutcome outcome = service.signIn(new SignInCommand(ADA, WRONG_PASSWORD));

    assertThat(accounts.find(ACME, before.id()).orElseThrow().active())
        .describedAs("the Admin's disable is not stored over")
        .isFalse();
    assertThat(outcome)
        .isInstanceOfSatisfying(
            SignInOutcome.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(SignInRefusal.DISABLED));
  }

  @Test
  void GivenAFailureCountedWhileThisAttemptWasChecked_WhenItFailsToo_ThenBothFailuresAreCounted() {
    Account before = accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()));
    accounts.signInReadsBeforeAChange(before);
    accounts.save(before.recordFailedSignIn(NOW, LOCK_AFTER_THREE));

    service.signIn(new SignInCommand(ADA, WRONG_PASSWORD));

    assertThat(accounts.find(ACME, before.id()).orElseThrow().failedSignIns()).isEqualTo(2);
  }

  /**
   * A reset is usually a response to somebody else knowing the old password. A session opened with
   * it after the reset committed would outlive the revocation the reset made, which is the one
   * thing the reset was for (R17).
   */
  @Test
  void
      GivenThePasswordResetWhileTheOldOneWasChecked_WhenSigningInWithTheOldOne_ThenNoSessionBegins() {
    Account before = accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()));
    accounts.signInReadsBeforeAChange(before);
    accounts.save(
        before.unlocked().withPassword(passwords.hash("a reset invented password"), true));

    SignInOutcome outcome = service.signIn(new SignInCommand(ADA, RIGHT_PASSWORD));

    assertThat(outcome)
        .isInstanceOfSatisfying(
            SignInOutcome.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(SignInRefusal.WRONG_PASSWORD));
    assertThat(sessions.started()).isEmpty();
  }

  @Test
  void
      GivenTheAccountDisabledWhileTheRightPasswordWasChecked_WhenSigningIn_ThenItIsRefusedAsDisabled() {
    Account before = accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()));
    accounts.signInReadsBeforeAChange(before);
    accounts.save(before.disabled());

    SignInOutcome outcome = service.signIn(new SignInCommand(ADA, RIGHT_PASSWORD));

    assertThat(outcome)
        .isInstanceOfSatisfying(
            SignInOutcome.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(SignInRefusal.DISABLED));
    assertThat(sessions.started()).isEmpty();
  }

  /**
   * A locked account is refused from the unlocked read, before anything is held. Holding it would
   * write - a transaction id, and its commit - where an attempt on an unknown name writes nothing,
   * and that difference would tell somebody guessing that the name is real (ADR-041).
   */
  @Test
  void GivenALockedAccount_WhenSigningIn_ThenNothingIsHeld() {
    accounts.given(
        tenant(ADA, Role.SCHEDULER, Optional.empty())
            .recordFailedSignIn(NOW, LOCK_AFTER_THREE)
            .recordFailedSignIn(NOW, LOCK_AFTER_THREE)
            .recordFailedSignIn(NOW, LOCK_AFTER_THREE));

    service.signIn(new SignInCommand(ADA, RIGHT_PASSWORD));

    assertThat(accounts.heldForUpdate()).isEmpty();
  }

  /** As a locked one: a departed colleague's name must not be told apart from nobody's. */
  @Test
  void GivenADisabledAccount_WhenSigningIn_ThenNothingIsHeld() {
    accounts.given(tenant(ADA, Role.SCHEDULER, Optional.empty()).disabled());

    service.signIn(new SignInCommand(ADA, RIGHT_PASSWORD));

    assertThat(accounts.heldForUpdate()).isEmpty();
  }

  /**
   * There is no row to hold, so an attempt on an unknown name holds nothing and makes the one
   * comparison every attempt makes.
   */
  @Test
  void GivenAnUnknownSignInName_WhenSigningIn_ThenNoAccountIsReadForUpdate() {
    service.signIn(new SignInCommand("nobody@example.com", RIGHT_PASSWORD));

    assertThat(accounts.heldForUpdate()).isEmpty();
    assertThat(passwords.comparisons()).isEqualTo(1);
  }

  private Account tenant(String name, Role role, Optional<EmployeeId> employee) {
    return new Account(
        UserId.newId(),
        new SignInName(name),
        passwords.hash(RIGHT_PASSWORD),
        new TenantMembership(ACME, role, employee),
        false,
        NOW.minus(Duration.ofDays(1)));
  }

  private Account operator(String name) {
    return new Account(
        UserId.newId(),
        new SignInName(name),
        passwords.hash(RIGHT_PASSWORD),
        new OperatorMembership(),
        false,
        NOW.minus(Duration.ofDays(1)));
  }

  /**
   * Remembers the sessions it was asked to start and the revocations it was asked to make, and lets
   * a test say that the conditional update found nothing to change.
   *
   * <p>That last part is the only way to reach the race the real adapter guards against: two
   * refreshes holding one token, where the database awards the exchange to one of them. Here the
   * test simply says the exchange was already taken.
   */
  private static final class StubSessionRepository implements SessionRepository {

    private final List<SignInSession> started = new ArrayList<>();
    private final Map<SignInSessionId, SessionRevocation> revocations = new LinkedHashMap<>();
    private final Map<String, StoredRefreshToken> tokens = new LinkedHashMap<>();
    private final Map<UserId, SessionRevocation> accountsWhoseSessionsEnded = new LinkedHashMap<>();
    private boolean exchangeAlreadyTaken;

    List<SignInSession> started() {
      return List.copyOf(started);
    }

    Optional<SessionRevocation> revocationOf(SignInSessionId session) {
      return Optional.ofNullable(revocations.get(session));
    }

    Optional<SessionRevocation> everySessionEndedFor(UserId account) {
      return Optional.ofNullable(accountsWhoseSessionsEnded.get(account));
    }

    /** Arranges the losing half of a race: the row was already marked by somebody else. */
    void whereTheExchangeIsAlreadyTaken() {
      exchangeAlreadyTaken = true;
    }

    void given(String tokenValue, StoredRefreshToken token) {
      tokens.put(tokenValue, token);
    }

    @Override
    public void start(SignInSession session, byte[] refreshDigest, Instant refreshExpiresAt) {
      started.add(session);
    }

    @Override
    public Optional<StoredRefreshToken> findRefreshToken(byte[] refreshDigest) {
      return Optional.ofNullable(tokens.get(new String(refreshDigest, StandardCharsets.UTF_8)));
    }

    @Override
    public boolean markReplaced(byte[] refreshDigest, Instant now) {
      return !exchangeAlreadyTaken;
    }

    @Override
    public void issueSuccessor(
        SignInSessionId session, byte[] refreshDigest, Instant issuedAt, Instant expiresAt) {
      // Nothing to remember: these tests are about which path was taken, not about what it wrote.
    }

    @Override
    public void revoke(SignInSessionId session, SessionRevocation reason, Instant now) {
      revocations.putIfAbsent(session, reason);
    }

    @Override
    public Optional<AuthenticatedSession> findAuthenticatedSession(
        UserId account, SignInSessionId session) {
      return Optional.empty();
    }

    @Override
    public void revokeEverySessionOf(UserId account, SessionRevocation reason, Instant now) {
      accountsWhoseSessionsEnded.put(account, reason);
    }
  }

  /** Issues a token that says what it is for, so a test can read it back without verifying one. */
  private static final class StubAccessTokenIssuer implements AccessTokenIssuer {

    @Override
    public IssuedAccessToken issue(UserId account, SignInSessionId session) {
      return new IssuedAccessToken(
          "token-for-%s-in-%s".formatted(account.value(), session.value()),
          NOW.plus(Duration.ofMinutes(10)));
    }
  }

  /** Hands out predictable secrets, so nothing in these tests depends on randomness. */
  private static final class StubSessionSecrets implements SessionSecrets {

    private int issued;

    @Override
    public RefreshTokenSecret generate() {
      issued++;
      String value = "refresh-" + issued;
      return new RefreshTokenSecret(value, digestOf(value));
    }

    @Override
    public byte[] digestOf(String value) {
      return value.getBytes(StandardCharsets.UTF_8);
    }
  }
}
