package io.github.naimelijah.hrsched.access.adapter.out.persistence;

import io.github.naimelijah.hrsched.access.application.port.out.AuthenticatedSession;
import io.github.naimelijah.hrsched.access.application.port.out.SessionRepository;
import io.github.naimelijah.hrsched.access.application.port.out.StoredRefreshToken;
import io.github.naimelijah.hrsched.access.domain.model.SessionRevocation;
import io.github.naimelijah.hrsched.access.domain.model.SignInSession;
import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing sign-in sessions and their refresh tokens in PostgreSQL.
 *
 * <p>Opening a session and issuing its first refresh token are one method rather than two, because
 * a session with no way to renew it is not a state anything wants: the caller runs in one
 * transaction, and this keeps the two writes from being separable by a later refactor.
 *
 * <p>Stores the digest of a refresh token and never the token. Nothing here can produce one.
 */
@Repository
public class JdbcSessionRepository implements SessionRepository {

  /**
   * The one query on the authenticated path. The session's {@code id} is aliased because both
   * tables have one, and a mapper reading the wrong one would be a bug nothing would show. Its
   * {@code expires_at} takes the alias {@code findRefreshToken}'s query also gives it, because a
   * refresh token has an {@code expires_at} of its own.
   */
  private static final String SELECT_AUTHENTICATED_SESSION =
      """
      SELECT a.id, a.sign_in_name, a.password_hash, a.kind, a.organisation_id, a.role,
             a.employee_id, a.active, a.password_change_required, a.failed_sign_ins,
             a.locked_until, a.created_at,
             s.id AS session_id, s.account_id, s.started_at,
             s.expires_at AS session_expires_at, s.revoked_at, s.revocation
      FROM sign_in_session s
      JOIN account a ON a.id = s.account_id
      WHERE s.id = :session AND s.account_id = :account
      """;

  private final JdbcClient jdbc;

  public JdbcSessionRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void start(SignInSession session, byte[] refreshDigest, Instant refreshExpiresAt) {
    Objects.requireNonNull(session, "session must not be null");
    Objects.requireNonNull(refreshDigest, "refreshDigest must not be null");
    Objects.requireNonNull(refreshExpiresAt, "refreshExpiresAt must not be null");
    jdbc.sql(
            """
            INSERT INTO sign_in_session (id, account_id, started_at, expires_at)
            VALUES (:id, :account, :startedAt, :expiresAt)
            """)
        .param("id", session.id().value())
        .param("account", session.account().value())
        .param("startedAt", session.startedAt().atOffset(ZoneOffset.UTC))
        .param("expiresAt", session.expiresAt().atOffset(ZoneOffset.UTC))
        .update();
    jdbc.sql(
            """
            INSERT INTO refresh_token (digest, session_id, issued_at, expires_at)
            VALUES (:digest, :session, :issuedAt, :expiresAt)
            """)
        .param("digest", refreshDigest)
        .param("session", session.id().value())
        .param("issuedAt", session.startedAt().atOffset(ZoneOffset.UTC))
        .param("expiresAt", refreshExpiresAt.atOffset(ZoneOffset.UTC))
        .update();
  }

  @Override
  public Optional<StoredRefreshToken> findRefreshToken(byte[] refreshDigest) {
    Objects.requireNonNull(refreshDigest, "refreshDigest must not be null");
    return jdbc.sql(
            """
            SELECT t.expires_at AS token_expires_at, t.replaced_at,
                   s.id AS session_id, s.account_id, s.started_at,
                   s.expires_at AS session_expires_at, s.revoked_at, s.revocation
            FROM refresh_token t
            JOIN sign_in_session s ON s.id = t.session_id
            WHERE t.digest = :digest
            """)
        .param("digest", refreshDigest)
        .query(JdbcSessionRepository::toStoredRefreshToken)
        .optional();
  }

  @Override
  public boolean markReplaced(byte[] refreshDigest, Instant now) {
    Objects.requireNonNull(refreshDigest, "refreshDigest must not be null");
    Objects.requireNonNull(now, "now must not be null");
    // The WHERE clause is the whole of it: the database decides which of two racing requests owns
    // the exchange, and the loser is told it changed nothing.
    return jdbc.sql(
                """
            UPDATE refresh_token SET replaced_at = :now
            WHERE digest = :digest AND replaced_at IS NULL
            """)
            .param("now", now.atOffset(ZoneOffset.UTC))
            .param("digest", refreshDigest)
            .update()
        == 1;
  }

  @Override
  public void issueSuccessor(
      SignInSessionId session, byte[] refreshDigest, Instant issuedAt, Instant expiresAt) {
    Objects.requireNonNull(session, "session must not be null");
    Objects.requireNonNull(refreshDigest, "refreshDigest must not be null");
    Objects.requireNonNull(issuedAt, "issuedAt must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    jdbc.sql(
            """
            INSERT INTO refresh_token (digest, session_id, issued_at, expires_at)
            VALUES (:digest, :session, :issuedAt, :expiresAt)
            """)
        .param("digest", refreshDigest)
        .param("session", session.value())
        .param("issuedAt", issuedAt.atOffset(ZoneOffset.UTC))
        .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
        .update();
  }

  @Override
  public void revoke(SignInSessionId session, SessionRevocation reason, Instant now) {
    Objects.requireNonNull(session, "session must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    Objects.requireNonNull(now, "now must not be null");
    // Only a session that is not already revoked: whatever ended it first is what ended it, and a
    // later sign-out must not overwrite the record of a reuse.
    jdbc.sql(
            """
            UPDATE sign_in_session SET revoked_at = :now, revocation = :reason
            WHERE id = :id AND revoked_at IS NULL
            """)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("reason", reason.name())
        .param("id", session.value())
        .update();
  }

  @Override
  public Optional<AuthenticatedSession> findAuthenticatedSession(
      UserId account, SignInSessionId session) {
    Objects.requireNonNull(account, "account must not be null");
    Objects.requireNonNull(session, "session must not be null");
    return jdbc.sql(SELECT_AUTHENTICATED_SESSION)
        .param("account", account.value())
        .param("session", session.value())
        .query(JdbcSessionRepository::toAuthenticatedSession)
        .optional();
  }

  @Override
  public void revokeEverySessionOf(UserId account, SessionRevocation reason, Instant now) {
    Objects.requireNonNull(account, "account must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    Objects.requireNonNull(now, "now must not be null");
    jdbc.sql(
            """
            UPDATE sign_in_session SET revoked_at = :now, revocation = :reason
            WHERE account_id = :account AND revoked_at IS NULL
            """)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("reason", reason.name())
        .param("account", account.value())
        .update();
  }

  private static AuthenticatedSession toAuthenticatedSession(ResultSet row, int rowNumber)
      throws SQLException {
    return new AuthenticatedSession(
        JdbcAccountRepository.readAccount(row),
        new SignInSession(
            new SignInSessionId(row.getObject("session_id", UUID.class)),
            new UserId(row.getObject("account_id", UUID.class)),
            instantOf(row, "started_at"),
            instantOf(row, "session_expires_at"),
            Optional.ofNullable(row.getString("revocation")).map(SessionRevocation::valueOf),
            optionalInstantOf(row, "revoked_at")));
  }

  private static StoredRefreshToken toStoredRefreshToken(ResultSet row, int rowNumber)
      throws SQLException {
    SignInSession session =
        new SignInSession(
            new SignInSessionId(row.getObject("session_id", UUID.class)),
            new UserId(row.getObject("account_id", UUID.class)),
            instantOf(row, "started_at"),
            instantOf(row, "session_expires_at"),
            Optional.ofNullable(row.getString("revocation")).map(SessionRevocation::valueOf),
            optionalInstantOf(row, "revoked_at"));
    return new StoredRefreshToken(
        session, instantOf(row, "token_expires_at"), optionalInstantOf(row, "replaced_at"));
  }

  private static Instant instantOf(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static Optional<Instant> optionalInstantOf(ResultSet row, String column)
      throws SQLException {
    return Optional.ofNullable(row.getObject(column, OffsetDateTime.class))
        .map(OffsetDateTime::toInstant);
  }
}
