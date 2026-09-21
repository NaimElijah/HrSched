package io.github.naimelijah.hrsched.access.adapter.in.web;

import io.github.naimelijah.hrsched.access.application.port.in.IssuedSession;
import java.time.Instant;

/**
 * What a successful sign-in answers with.
 *
 * <p>The refresh token is deliberately absent: it goes in a cookie the browser cannot read, so that
 * a script which manages to read this response still cannot renew a session with it (ADR-028).
 *
 * <p>{@code expiresIn} is seconds rather than an instant, so a client need not have a correct clock
 * to know when to renew.
 *
 * @param accessToken the token every request carries
 * @param tokenType always {@code Bearer}, as RFC 6750 names it
 * @param expiresIn seconds until the access token expires
 * @param session who is signed in
 */
public record SignInResponse(
    String accessToken, String tokenType, long expiresIn, SessionResponse session) {

  private static final String BEARER = "Bearer";

  /**
   * @param issued what the use case handed back
   * @param now the instant the expiry is measured from
   * @return the same thing, shaped for JSON, without the refresh token
   */
  static SignInResponse of(IssuedSession issued, Instant now) {
    return new SignInResponse(
        issued.accessToken(),
        BEARER,
        java.time.Duration.between(now, issued.accessTokenExpiresAt()).toSeconds(),
        SessionResponse.of(issued.session()));
  }
}
