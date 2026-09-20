package io.github.naimelijah.hrsched.access.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of one sign-in session: the span between somebody signing in and that session ending.
 *
 * <p>Distinct from the account, because one account may be signed in from a phone and a laptop at
 * once and either may be ended without touching the other. Every access token names the session it
 * belongs to, which is what makes signing out take effect: the session is what gets revoked, not
 * the token, and a token whose session has ended is refused on its next use.
 *
 * @param value the underlying identifier, never {@code null}
 */
public record SignInSessionId(UUID value) {

  public SignInSessionId {
    Objects.requireNonNull(value, "value must not be null");
  }

  /**
   * @return a fresh identity backed by a random UUID
   */
  public static SignInSessionId newId() {
    return new SignInSessionId(UUID.randomUUID());
  }
}
