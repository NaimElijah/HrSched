package io.github.naimelijah.hrsched.access.adapter.in.web;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;

/**
 * The cookie the refresh token travels in, and the only place its attributes are decided.
 *
 * <p>Every attribute here is doing work (ADR-028):
 *
 * <ul>
 *   <li><strong>HttpOnly</strong> - script cannot read it, so a cross-site scripting flaw that can
 *       read the page cannot walk away with the means to renew a session indefinitely. This is why
 *       the refresh token is a cookie at all while the access token is not.
 *   <li><strong>Secure</strong> - never sent over plain HTTP, so it cannot be read off the wire.
 *   <li><strong>SameSite=Strict</strong> - not sent on any cross-site request, so another origin
 *       cannot cause a renewal by navigating to one.
 *   <li><strong>Path=/api/auth</strong> - sent only to the session endpoints under that path:
 *       signing in, renewing, signing out, reading the session and changing one's own password, of
 *       which only renewing and signing out read it. It is absent from every other API request, so
 *       none of those can leak it.
 *   <li><strong>__Secure- prefix</strong> - browsers refuse to set a cookie so named unless it is
 *       Secure, which stops a network attacker who can forge plain HTTP from overwriting it.
 *   <li><strong>Max-Age</strong> - the browser drops it when the token expires anyway.
 * </ul>
 *
 * <p>Package-private: nothing outside this adapter should be able to write this cookie, and nothing
 * outside it should have an opinion about these attributes.
 */
final class RefreshCookie {

  /**
   * The {@code __Secure-} prefix is part of the name, not decoration: a browser enforces the Secure
   * attribute because of it.
   */
  static final String NAME = "__Secure-hrsched-refresh";

  /** Narrow on purpose: the token is only ever spent renewing or ending a session. */
  private static final String PATH = "/api/auth";

  private static final String SAME_SITE = "Strict";

  private RefreshCookie() {}

  /**
   * @param value the refresh token to hand to the browser
   * @param now the instant the lifetime is measured from
   * @param expiresAt when the token stops being accepted
   * @return the cookie to set
   */
  static ResponseCookie issuing(String value, Instant now, Instant expiresAt) {
    return base(value).maxAge(Duration.between(now, expiresAt)).build();
  }

  /**
   * @return a cookie of the same name, path and attributes with nothing in it and no life, which is
   *     how a browser is told to forget the one it has
   */
  static ResponseCookie clearing() {
    return base("").maxAge(Duration.ZERO).build();
  }

  private static ResponseCookie.ResponseCookieBuilder base(String value) {
    return ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .secure(true)
        .sameSite(SAME_SITE)
        .path(PATH);
  }
}
