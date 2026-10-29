package io.github.naimelijah.hrsched.access.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.PasswordHash;
import io.github.naimelijah.hrsched.access.domain.model.SessionRevocation;
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
   * The conditional update is the whole of the race protection: whichever of two requests changes
   * the row owns the exchange, and the other is told it changed nothing - which is what the service
   * reads as somebody presenting a token that had already been spent.
   */
  @Test
  void GivenATokenAlreadyReplaced_WhenMarkingItReplacedAgain_ThenNoRowIsUpdated() {
    SignInSession session = sessionFor(anAccount());
    byte[] token = digest("a-refresh-token");
    repository.start(session, token, NOW.plus(Duration.ofHours(1)));

    assertThat(repository.markReplaced(token, NOW.plusSeconds(60)))
        .describedAs("the first exchange wins")
        .isTrue();

    assertThat(repository.markReplaced(token, NOW.plusSeconds(61)))
        .describedAs("the second finds nothing left to mark")
        .isFalse();
  }

  @Test
  void GivenNoSuchToken_WhenMarkingItReplaced_ThenNoRowIsUpdated() {
    assertThat(repository.markReplaced(digest("a-token-nobody-issued"), NOW)).isFalse();
  }

  @Test
  void GivenAnExchangedToken_WhenASuccessorIsIssued_ThenItBelongsToTheSameSession() {
    SignInSession session = sessionFor(anAccount());
    repository.start(session, digest("first"), NOW.plus(Duration.ofHours(1)));

    repository.issueSuccessor(
        session.id(), digest("second"), NOW.plusSeconds(60), NOW.plus(Duration.ofHours(2)));

    assertThat(sessionOfRefreshToken(digest("second"))).isEqualTo(session.id().value());
  }

  @Test
  void GivenALiveSession_WhenRevoked_ThenItSaysWhenAndWhy() {
    SignInSession session = sessionFor(anAccount());
    repository.start(session, digest("a-refresh-token"), NOW.plus(Duration.ofHours(1)));

    repository.revoke(session.id(), SessionRevocation.SIGNED_OUT, NOW.plusSeconds(60));

    assertThat(storedSessionRevocation(session.id())).isEqualTo("SIGNED_OUT");
    assertThat(repository.findRefreshToken(digest("a-refresh-token")).orElseThrow().session())
        .satisfies(
            revoked ->
                assertThat(revoked.isLiveAt(NOW.plusSeconds(61)))
                    .describedAs("a revoked session is no longer live, whatever its expiry says")
                    .isFalse());
  }

  /**
   * Whatever ended a session first is what ended it. A later sign-out must not overwrite the record
   * of a reuse, because that record is the evidence something was stolen.
   */
  @Test
  void GivenAnAlreadyRevokedSession_WhenRevokedAgain_ThenTheFirstReasonStands() {
    SignInSession session = sessionFor(anAccount());
    repository.start(session, digest("a-refresh-token"), NOW.plus(Duration.ofHours(1)));
    repository.revoke(session.id(), SessionRevocation.REFRESH_REUSED, NOW.plusSeconds(60));

    repository.revoke(session.id(), SessionRevocation.SIGNED_OUT, NOW.plusSeconds(120));

    assertThat(storedSessionRevocation(session.id())).isEqualTo("REFRESH_REUSED");
  }

  /**
   * The authenticated read asks for the account and the session together, so a token pairing one
   * account with another's session finds nothing, while each with its own is found.
   */
  @Test
  void GivenASessionOfAnotherAccount_WhenFindingItAsTheAuthenticatedSession_ThenNothingIsFound() {
    Account mine = anAccount();
    Account theirs = anAccount();
    SignInSession theirSession = sessionFor(theirs);
    repository.start(theirSession, digest("their-refresh-token"), NOW.plus(Duration.ofHours(1)));

    assertThat(repository.findAuthenticatedSession(mine.id(), theirSession.id())).isEmpty();
    assertThat(repository.findAuthenticatedSession(theirs.id(), theirSession.id()))
        .describedAs("the same session, with its own account, is found")
        .isPresent();
  }

  /** Ending every session of one account ends no session of anybody else's. */
  @Test
  void GivenTwoAccountsWithSessions_WhenEverySessionOfOneIsRevoked_ThenTheOthersAreUntouched() {
    Account ending = anAccount();
    Account bystander = anAccount();
    SignInSession laptop = sessionFor(ending);
    SignInSession phone = sessionFor(ending);
    SignInSession theirs = sessionFor(bystander);
    repository.start(laptop, digest("laptop"), NOW.plus(Duration.ofHours(1)));
    repository.start(phone, digest("phone"), NOW.plus(Duration.ofHours(1)));
    repository.start(theirs, digest("theirs"), NOW.plus(Duration.ofHours(1)));

    repository.revokeEverySessionOf(
        ending.id(), SessionRevocation.PASSWORD_CHANGED, NOW.plusSeconds(60));

    assertThat(storedSessionRevocation(laptop.id())).isEqualTo("PASSWORD_CHANGED");
    assertThat(storedSessionRevocation(phone.id())).isEqualTo("PASSWORD_CHANGED");
    assertThat(storedSessionRevocation(theirs.id())).isNull();
  }

  @Test
  void GivenNoSuchToken_WhenFindingIt_ThenNothingIsReturned() {
    assertThat(repository.findRefreshToken(digest("a-token-nobody-issued"))).isEmpty();
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
