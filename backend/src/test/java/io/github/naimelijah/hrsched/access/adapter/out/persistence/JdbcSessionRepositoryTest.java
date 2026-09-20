package io.github.naimelijah.hrsched.access.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.PasswordHash;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.SignInSession;
import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What starting a session actually writes.
 *
 * <p>A refresh token is found by its digest and never by itself, which is the only way the real
 * code ever looks one up. That the digest is a real hash rather than the token is {@code
 * SecureRandomSessionSecrets}'s promise; this adapter stores whatever it is handed.
 *
 * <p>Every value here is invented for the test and hashes nothing.
 */
class JdbcSessionRepositoryTest {

  private static final Instant NOW = Instant.parse("2026-09-20T09:00:00Z");
  private static final PasswordHash HASH = new PasswordHash("{bcrypt}$2a$04$invented.test.value");

  private final JdbcAccountRepository accounts =
      new JdbcAccountRepository(PostgresDatabase.jdbcClient());
  private final JdbcSessionRepository repository =
      new JdbcSessionRepository(PostgresDatabase.jdbcClient());

  @BeforeEach
  void emptyTheDatabase() {
    PostgresDatabase.empty();
  }

  @Test
  void GivenAStartedSession_WhenReadBack_ThenItsAccountAndLifetimeAreStored() {
    Account account = anAccount();
    SignInSession session = sessionFor(account);

    repository.start(session, digest("a-refresh-token"), NOW.plus(Duration.ofHours(1)));

    assertThat(storedSessionAccount(session.id())).isEqualTo(account.id().value());
    assertThat(storedSessionRevocation(session.id()))
        .describedAs("a session is live until something ends it")
        .isNull();
  }

  @Test
  void GivenAStartedSession_WhenReadBack_ThenItsFirstRefreshTokenIsStoredAsADigest() {
    Account account = anAccount();
    SignInSession session = sessionFor(account);
    byte[] digest = digest("a-refresh-token");

    repository.start(session, digest, NOW.plus(Duration.ofHours(1)));

    assertThat(sessionOfRefreshToken(digest))
        .describedAs("a token is found by its digest, which is the only way the real code looks")
        .isEqualTo(session.id().value());
  }

  /**
   * A session belongs to an account that exists. The foreign key is what makes a session for nobody
   * unstorable rather than merely unlikely.
   */
  @Test
  void GivenNoSuchAccount_WhenStartingASession_ThenItIsRefused() {
    SignInSession orphan =
        new SignInSession(
            SignInSessionId.newId(), UserId.newId(), NOW, NOW.plus(Duration.ofHours(12)));

    assertThatThrownBy(
            () ->
                repository.start(orphan, digest("a-refresh-token"), NOW.plus(Duration.ofHours(1))))
        .isInstanceOf(RuntimeException.class);
  }

  private Account anAccount() {
    Account account =
        new Account(
            UserId.newId(),
            new SignInName("operator-%s@example.com".formatted(UUID.randomUUID())),
            HASH,
            new OperatorMembership(),
            false,
            NOW.minus(Duration.ofDays(1)));
    accounts.save(account);
    return account;
  }

  private static SignInSession sessionFor(Account account) {
    return new SignInSession(
        SignInSessionId.newId(), account.id(), NOW, NOW.plus(Duration.ofHours(12)));
  }

  private static byte[] digest(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static UUID storedSessionAccount(SignInSessionId session) {
    return PostgresDatabase.jdbcClient()
        .sql("SELECT account_id FROM sign_in_session WHERE id = :id")
        .param("id", session.value())
        .query(UUID.class)
        .single();
  }

  private static String storedSessionRevocation(SignInSessionId session) {
    return PostgresDatabase.jdbcClient()
        .sql("SELECT revocation FROM sign_in_session WHERE id = :id")
        .param("id", session.value())
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private static UUID sessionOfRefreshToken(byte[] digest) {
    return PostgresDatabase.jdbcClient()
        .sql("SELECT session_id FROM refresh_token WHERE digest = :digest")
        .param("digest", digest)
        .query(UUID.class)
        .single();
  }
}
