package io.github.naimelijah.hrsched.access.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.access.application.port.in.AccountKind;
import io.github.naimelijah.hrsched.access.application.port.in.SignInCommand;
import io.github.naimelijah.hrsched.access.application.port.in.SignInOutcome;
import io.github.naimelijah.hrsched.access.application.port.in.SignInRefusal;
import io.github.naimelijah.hrsched.access.application.port.out.AccessChange;
import io.github.naimelijah.hrsched.access.application.port.out.AccessTokenIssuer;
import io.github.naimelijah.hrsched.access.application.port.out.IssuedAccessToken;
import io.github.naimelijah.hrsched.access.application.port.out.RefreshTokenSecret;
import io.github.naimelijah.hrsched.access.application.port.out.SessionRepository;
import io.github.naimelijah.hrsched.access.application.port.out.SessionSecrets;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.LockoutPolicy;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.SessionPolicy;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.SignInSession;
import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
  private static final OrganisationId ACME = OrganisationId.newId();
  private static final String ADA = "ada@example.com";
  private static final String RIGHT_PASSWORD = "an invented password for a test";
  private static final String WRONG_PASSWORD = "not the invented password";
  private static final LockoutPolicy LOCK_AFTER_THREE =
      new LockoutPolicy(3, Duration.ofMinutes(15));
  private static final SessionPolicy A_DAY_AND_AN_HOUR =
      new SessionPolicy(Duration.ofHours(12), Duration.ofHours(1));

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
        policy);
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
            tenant(ADA, Role.EMPLOYEE, Optional.empty())
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

  @Test
  void GivenADisabledAccount_WhenSigningInWithTheRightPassword_ThenItIsRefused() {
    accounts.given(tenant(ADA, Role.EMPLOYEE, Optional.empty()).disabled());

    SignInOutcome outcome = service.signIn(new SignInCommand(ADA, RIGHT_PASSWORD));

    assertThat(outcome)
        .isInstanceOfSatisfying(
            SignInOutcome.Refused.class,
            refused -> assertThat(refused.reason()).isEqualTo(SignInRefusal.DISABLED));
    assertThat(sessions.started()).isEmpty();
  }

  @Test
  void GivenAWrongPassword_WhenRefused_ThenTheFailureIsCounted() {
    accounts.given(tenant(ADA, Role.EMPLOYEE, Optional.empty()));

    service.signIn(new SignInCommand(ADA, WRONG_PASSWORD));

    assertThat(accounts.findBySignInName(new SignInName(ADA)).orElseThrow().failedSignIns())
        .isEqualTo(1);
  }

  @Test
  void GivenTheLockingFailure_WhenRecorded_ThenAnAccountLockedEntryIsAudited() {
    accounts.given(tenant(ADA, Role.EMPLOYEE, Optional.empty()));

    service.signIn(new SignInCommand(ADA, WRONG_PASSWORD));
    service.signIn(new SignInCommand(ADA, WRONG_PASSWORD));
    assertThat(audit.changes()).doesNotContain(AccessChange.ACCOUNT_LOCKED);

    service.signIn(new SignInCommand(ADA, WRONG_PASSWORD));

    assertThat(audit.of(AccessChange.ACCOUNT_LOCKED)).hasSize(1);
  }

  @Test
  void GivenEarlierFailures_WhenSigningInSuccessfully_ThenTheCountResets() {
    accounts.given(tenant(ADA, Role.EMPLOYEE, Optional.empty()));
    service.signIn(new SignInCommand(ADA, WRONG_PASSWORD));

    service.signIn(new SignInCommand(ADA, RIGHT_PASSWORD));

    assertThat(accounts.findBySignInName(new SignInName(ADA)).orElseThrow().failedSignIns())
        .isZero();
  }

  @Test
  void
      GivenASignIn_WhenItSucceeds_ThenTheRefreshTokenExpiresAtTheIdleLimitOrTheSessionEndWhicheverIsSooner() {
    accounts.given(tenant(ADA, Role.EMPLOYEE, Optional.empty()));

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
    accounts.given(tenant(ADA, Role.EMPLOYEE, Optional.empty()));
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
    Account account = accounts.given(tenant(ADA, Role.EMPLOYEE, Optional.empty()));

    service.signIn(new SignInCommand(ADA, RIGHT_PASSWORD));

    assertThat(audit.of(AccessChange.SIGNED_IN)).hasSize(1);
    assertThat(audit.of(AccessChange.SIGNED_IN).getFirst().subject())
        .isNotEqualTo(account.id().value());
    assertThat(audit.of(AccessChange.SIGNED_IN).getFirst().organisation()).contains(ACME);
  }

  @Test
  void GivenAMixedCaseSignInName_WhenSigningIn_ThenItMatches() {
    accounts.given(tenant(ADA, Role.EMPLOYEE, Optional.empty()));

    assertThat(service.signIn(new SignInCommand("  ADA@Example.COM ", RIGHT_PASSWORD)))
        .isInstanceOf(SignInOutcome.SignedIn.class);
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

  /** Remembers the sessions it was asked to start, and what they were given to renew with. */
  private static final class StubSessionRepository implements SessionRepository {

    private final List<SignInSession> started = new ArrayList<>();

    List<SignInSession> started() {
      return List.copyOf(started);
    }

    @Override
    public void start(SignInSession session, byte[] refreshDigest, Instant refreshExpiresAt) {
      started.add(session);
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
