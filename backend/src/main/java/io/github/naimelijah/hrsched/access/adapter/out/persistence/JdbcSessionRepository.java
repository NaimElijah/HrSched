package io.github.naimelijah.hrsched.access.adapter.out.persistence;

import io.github.naimelijah.hrsched.access.application.port.out.SessionRepository;
import io.github.naimelijah.hrsched.access.domain.model.SignInSession;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
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
}
