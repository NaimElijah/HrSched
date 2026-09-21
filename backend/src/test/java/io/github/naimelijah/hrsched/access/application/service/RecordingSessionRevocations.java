package io.github.naimelijah.hrsched.access.application.service;

import io.github.naimelijah.hrsched.access.application.port.out.AuthenticatedSession;
import io.github.naimelijah.hrsched.access.application.port.out.SessionRepository;
import io.github.naimelijah.hrsched.access.application.port.out.StoredRefreshToken;
import io.github.naimelijah.hrsched.access.domain.model.SessionRevocation;
import io.github.naimelijah.hrsched.access.domain.model.SignInSession;
import io.github.naimelijah.hrsched.access.domain.model.SignInSessionId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Remembers which accounts were signed out of everywhere, and refuses the rest of the port.
 *
 * <p>Account administration ends sessions and never reads one, so exactly one method here does
 * anything. The others throw rather than quietly returning nothing: a fake that answered them would
 * let a use case start reading sessions without a single test noticing, and refusing loudly is the
 * honest way to say these tests are not about that.
 *
 * <p>Named without a {@code Test} suffix so Surefire does not try to run it.
 */
final class RecordingSessionRevocations implements SessionRepository {

  private final Map<UserId, SessionRevocation> ended = new LinkedHashMap<>();

  /**
   * @param account whose sessions to ask about
   * @return why they were all ended, or empty if they were not
   */
  Optional<SessionRevocation> everySessionEndedFor(UserId account) {
    return Optional.ofNullable(ended.get(account));
  }

  @Override
  public void revokeEverySessionOf(UserId account, SessionRevocation reason, Instant now) {
    ended.put(account, reason);
  }

  @Override
  public void start(SignInSession session, byte[] refreshDigest, Instant refreshExpiresAt) {
    throw notAboutSessions();
  }

  @Override
  public Optional<StoredRefreshToken> findRefreshToken(byte[] refreshDigest) {
    throw notAboutSessions();
  }

  @Override
  public boolean markReplaced(byte[] refreshDigest, Instant now) {
    throw notAboutSessions();
  }

  @Override
  public void issueSuccessor(
      SignInSessionId session, byte[] refreshDigest, Instant issuedAt, Instant expiresAt) {
    throw notAboutSessions();
  }

  @Override
  public void revoke(SignInSessionId session, SessionRevocation reason, Instant now) {
    throw notAboutSessions();
  }

  @Override
  public Optional<AuthenticatedSession> findAuthenticatedSession(
      UserId account, SignInSessionId session) {
    throw notAboutSessions();
  }

  private static UnsupportedOperationException notAboutSessions() {
    return new UnsupportedOperationException(
        "These use cases do not read sessions; they only end them");
  }
}
