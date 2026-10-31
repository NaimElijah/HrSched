package io.github.naimelijah.hrsched.access.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.access.adapter.out.persistence.JdbcAccountRepository;
import io.github.naimelijah.hrsched.access.adapter.out.persistence.JdbcSessionRepository;
import io.github.naimelijah.hrsched.access.application.port.out.IssuedAccessToken;
import io.github.naimelijah.hrsched.access.application.port.out.RefreshTokenSecret;
import io.github.naimelijah.hrsched.access.application.port.out.SessionSecrets;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.LockoutPolicy;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.PasswordPolicy;
import io.github.naimelijah.hrsched.access.domain.model.SessionPolicy;
import io.github.naimelijah.hrsched.access.domain.model.SessionRevocation;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.SignInSession;
import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ending every session of an account holds on every instance, whatever each instance's clock reads.
 *
 * <p>The revocation is stamped by the instance that served the reset, while whether a session is
 * still live is judged by whichever instance serves the next request, on its own clock. Two wall
 * clocks never exactly agree, so a revocation that skipped sessions its own clock called expired
 * would leave one that another instance, a little behind, still serves. Driven through the real
 * session adapter, so what is asserted is what the store holds, and through {@link SessionService}
 * on a second clock, which is how another instance reads it.
 *
 * <p>Every instant, name, password and policy number here is invented for the test.
 */
class SessionRevocationAcrossClocksTest {

  /** Invented: the instant the session ends. */
  private static final Instant SESSION_ENDS = Instant.parse("2026-09-20T09:00:00Z");

  /** Invented: how far apart the two instances' clocks are, either side of the session's end. */
  private static final Duration SKEW = Duration.ofMillis(5);

  /** Invented. */
  private static final LockoutPolicy LOCK_AFTER_THREE =
      new LockoutPolicy(3, Duration.ofMinutes(15));

  /** Invented: a session lasts twelve hours at most, and an hour without being renewed. */
  private static final SessionPolicy A_TWELVE_HOUR_SESSION_IDLE_AFTER_AN_HOUR =
      new SessionPolicy(Duration.ofHours(12), Duration.ofHours(1));

  /** Invented. */
  private static final PasswordPolicy A_FIFTEEN_CHARACTER_MINIMUM = new PasswordPolicy(15, 72);

  /** Invented. */
  private static final String PASSWORD = "an invented password for a test";

  private final JdbcAccountRepository accounts =
      new JdbcAccountRepository(PostgresDatabase.jdbcClient());
  private final JdbcSessionRepository sessions =
      new JdbcSessionRepository(PostgresDatabase.jdbcClient());
  private final StubPasswordHasher passwords = new StubPasswordHasher();

  @BeforeEach
  void emptyTheDatabase() {
    PostgresDatabase.empty();
  }

  /**
   * The reset is served by an instance whose clock is already past the session's end, and the next
   * request by one whose clock is not there yet. The slower one must find the session ended.
   */
  @Test
  void GivenAResetOnAnInstanceAheadOfAnother_WhenTheOtherReadsTheSession_ThenItFindsNoActorForIt() {
    Account account = anActiveAccount();
    SignInSession session =
        new SignInSession(
            SignInSessionId.newId(),
            account.id(),
            SESSION_ENDS.minus(Duration.ofHours(12)),
            SESSION_ENDS);
    sessions.start(session, "a-refresh-token".getBytes(StandardCharsets.UTF_8), SESSION_ENDS);
    SessionService behind = serviceAt(SESSION_ENDS.minus(SKEW));
    assertThat(behind.findActor(account.id(), session.id()))
        .describedAs("before the reset, the instance behind still serves the session")
        .isPresent();

    sessions.revokeEverySessionOf(
        account.id(), SessionRevocation.PASSWORD_RESET, SESSION_ENDS.plus(SKEW));

    assertThat(behind.findActor(account.id(), session.id()))
        .describedAs("the reset ended the session on every instance, not only on the one ahead")
        .isEmpty();
  }

  private Account anActiveAccount() {
    Account account =
        new Account(
            UserId.newId(),
            new SignInName("operator-%s@example.com".formatted(UserId.newId().value())),
            passwords.hash(PASSWORD),
            new OperatorMembership(),
            false,
            SESSION_ENDS.minus(Duration.ofDays(1)));
    accounts.save(account);
    return account;
  }

  private SessionService serviceAt(Instant now) {
    return new SessionService(
        accounts,
        sessions,
        passwords,
        (account, session) -> new IssuedAccessToken("unused", now),
        new FixedSessionSecrets(),
        new RecordingAuditTrail(),
        Clock.fixed(now, ZoneOffset.UTC),
        LOCK_AFTER_THREE,
        A_TWELVE_HOUR_SESSION_IDLE_AFTER_AN_HOUR,
        A_FIFTEEN_CHARACTER_MINIMUM);
  }

  /** One fixed secret, so nothing here depends on randomness; reading a session issues none. */
  private static final class FixedSessionSecrets implements SessionSecrets {

    /** Invented. */
    private static final String SECRET = "an invented refresh secret";

    @Override
    public RefreshTokenSecret generate() {
      return new RefreshTokenSecret(SECRET, digestOf(SECRET));
    }

    @Override
    public byte[] digestOf(String value) {
      return value.getBytes(StandardCharsets.UTF_8);
    }
  }
}
