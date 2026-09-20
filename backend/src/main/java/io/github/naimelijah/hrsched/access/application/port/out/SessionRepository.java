package io.github.naimelijah.hrsched.access.application.port.out;

import io.github.naimelijah.hrsched.access.domain.model.SignInSession;
import java.time.Instant;

/**
 * Driven port: storing sign-in sessions and the refresh tokens that renew them.
 *
 * <p>Deliberately not scoped by organisation, unlike every other repository here. A session belongs
 * to an account rather than to a tenant, and the operator's sessions belong to no tenant at all;
 * scoping would be a filter that could not be written for half the rows. What keeps it safe is that
 * a session is only ever reached by an identifier that came out of a verified token, or by the
 * account it belongs to.
 */
public interface SessionRepository {

  /**
   * Opens a session and issues its first refresh token, as one act.
   *
   * @param session the session being opened, never {@code null}
   * @param refreshDigest the digest of its first refresh token, never {@code null}
   * @param refreshExpiresAt when that token stops being accepted, never {@code null}
   */
  void start(SignInSession session, byte[] refreshDigest, Instant refreshExpiresAt);
}
