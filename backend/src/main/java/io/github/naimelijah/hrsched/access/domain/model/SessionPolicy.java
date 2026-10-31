package io.github.naimelijah.hrsched.access.domain.model;

import java.time.Duration;
import java.util.Objects;

/**
 * How long a session may last, and how long it may sit idle.
 *
 * <p>Two limits rather than one, because they answer different questions. The absolute lifetime is
 * fixed when a session starts and is never extended, so a stolen refresh token cannot be used to
 * keep a session alive for ever by using it. The idle limit is how long a session survives without
 * being renewed, so a browser somebody walked away from stops working long before that.
 *
 * <p>Neither number has a default here, and neither appears as a constant anywhere: how long a
 * signed-in session should last is a risk decision for whoever deploys this.
 *
 * @param absoluteLifetime how long a session may live in total, strictly positive
 * @param idleLifetime how long it survives without renewal, positive and never longer than the
 *     absolute lifetime
 */
public record SessionPolicy(Duration absoluteLifetime, Duration idleLifetime) {

  /**
   * @throws NullPointerException if either duration is null
   * @throws IllegalArgumentException if either duration is zero or negative, or the idle lifetime
   *     is longer than the absolute one
   */
  public SessionPolicy {
    Objects.requireNonNull(absoluteLifetime, "absoluteLifetime must not be null");
    Objects.requireNonNull(idleLifetime, "idleLifetime must not be null");
    if (absoluteLifetime.isZero() || absoluteLifetime.isNegative()) {
      throw new IllegalArgumentException("A session's absolute lifetime must be positive");
    }
    if (idleLifetime.isZero() || idleLifetime.isNegative()) {
      throw new IllegalArgumentException("A session's idle lifetime must be positive");
    }
    if (idleLifetime.compareTo(absoluteLifetime) > 0) {
      throw new IllegalArgumentException(
          "A session's idle lifetime cannot be longer than its absolute lifetime");
    }
  }
}
