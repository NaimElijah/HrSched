package io.github.naimelijah.hrsched.access.application.port.out;

import java.time.Instant;
import java.util.Objects;

/**
 * An access token that has just been signed, and the instant it stops being valid.
 *
 * <p>The expiry travels beside the token rather than being read back out of it, so a caller that
 * has to tell a client when to renew does not have to parse what it just produced.
 *
 * <p>{@link #toString()} does not reveal the value. Whoever holds this string is the user until it
 * expires, so it is a credential and never belongs in a log.
 *
 * @param value the signed token, never {@code null}, never blank
 * @param expiresAt when it stops being accepted, never {@code null}
 */
public record IssuedAccessToken(String value, Instant expiresAt) {

  public IssuedAccessToken {
    Objects.requireNonNull(value, "value must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException("An issued access token must not be blank");
    }
  }

  @Override
  public String toString() {
    return "IssuedAccessToken[expiresAt=%s]".formatted(expiresAt);
  }
}
