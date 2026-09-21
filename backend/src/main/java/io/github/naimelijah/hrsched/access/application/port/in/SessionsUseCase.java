package io.github.naimelijah.hrsched.access.application.port.in;

/**
 * Driving port: renewing a session, and ending one.
 *
 * <p>Both take the refresh token as an opaque string, because that is all the caller has: the
 * browser holds it in a cookie it cannot read, and hands it back without knowing what it is.
 */
public interface SessionsUseCase {

  /**
   * Exchanges a refresh token for a new pair, if it is still good.
   *
   * <p>The token is always exchanged rather than reused, so each one works exactly once. A token
   * presented twice therefore means two parties hold it, and the only safe reading is that one of
   * them stole it — so the whole session ends rather than just that request being refused.
   *
   * @param refreshToken the token presented, never {@code null}
   * @return the new tokens, or the refusal and why
   */
  RefreshOutcome refresh(String refreshToken);

  /**
   * Ends the session a refresh token belongs to.
   *
   * <p>Idempotent, and deliberately silent: a token nobody issued, one already exchanged, and a
   * session already over all leave nothing to do. Signing out is not a place to tell a caller
   * whether what they presented was ever real.
   *
   * @param refreshToken the token presented, never {@code null}
   */
  void signOut(String refreshToken);
}
