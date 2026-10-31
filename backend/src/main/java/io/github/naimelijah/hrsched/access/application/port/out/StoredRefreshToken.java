package io.github.naimelijah.hrsched.access.application.port.out;

import io.github.naimelijah.hrsched.access.domain.model.SignInSession;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A stored refresh token, and the session it belongs to.
 *
 * <p>The two are read together because every decision about a token needs both: whether the token
 * itself has expired, and whether the session it renews is still live. Reading them separately
 * would be two round trips and, worse, two moments at which the answer could differ.
 *
 * <p>Carries no token value. There is none stored to carry.
 *
 * @param session the session this token renews
 * @param expiresAt when this token stops being accepted, which is never later than the session ends
 * @param replacedAt when it was exchanged for a successor, empty while it is still the current one
 */
public record StoredRefreshToken(
    SignInSession session, Instant expiresAt, Optional<Instant> replacedAt) {

  /**
   * @throws NullPointerException if {@code session}, {@code expiresAt} or {@code replacedAt} is
   *     null
   */
  public StoredRefreshToken {
    Objects.requireNonNull(session, "session must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    Objects.requireNonNull(replacedAt, "replacedAt must not be null");
  }

  /**
   * @return whether this token has already been exchanged, which means somebody is presenting a
   *     copy of a token that is no longer current
   */
  public boolean isReplaced() {
    return replacedAt.isPresent();
  }

  /**
   * @param now the instant being asked about
   * @return whether the token itself has run out
   */
  public boolean hasExpiredAt(Instant now) {
    return !now.isBefore(expiresAt);
  }
}
