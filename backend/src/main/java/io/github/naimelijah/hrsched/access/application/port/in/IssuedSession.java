package io.github.naimelijah.hrsched.access.application.port.in;

import java.time.Instant;
import java.util.Objects;

/**
 * Everything a client is handed when a session begins: two credentials and who it is signed in as.
 *
 * <p>The two tokens are deliberately different things. The access token is short-lived and is what
 * every request carries; the refresh token is longer-lived and is presented only to renew the
 * session or to end it ({@link SessionsUseCase}), and no other use case reads it. Their expiries
 * travel with them so that a client does not have to read a token to know when to act.
 *
 * <p>{@link #toString()} reveals neither token. Whoever holds either of them is the user.
 *
 * @param accessToken the token every request carries
 * @param accessTokenExpiresAt when it stops being accepted
 * @param refreshToken the token that renews the session
 * @param refreshTokenExpiresAt when that stops being accepted
 * @param session who is signed in
 */
public record IssuedSession(
    String accessToken,
    Instant accessTokenExpiresAt,
    String refreshToken,
    Instant refreshTokenExpiresAt,
    PublishedSession session) {

  public IssuedSession {
    Objects.requireNonNull(accessToken, "accessToken must not be null");
    Objects.requireNonNull(accessTokenExpiresAt, "accessTokenExpiresAt must not be null");
    Objects.requireNonNull(refreshToken, "refreshToken must not be null");
    Objects.requireNonNull(refreshTokenExpiresAt, "refreshTokenExpiresAt must not be null");
    Objects.requireNonNull(session, "session must not be null");
  }

  @Override
  public String toString() {
    return "IssuedSession[redacted]";
  }
}
