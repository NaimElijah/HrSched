package io.github.naimelijah.hrsched.access.domain.model;

import java.time.Duration;
import java.util.Objects;

/**
 * How many consecutive failed sign-ins lock an account, and for how long.
 *
 * <p>Neither number has a default here, and neither ever appears as a constant in this codebase.
 * How much guessing is too much, and how long a locked-out person waits, are decisions for whoever
 * deploys this — the same rule the working-time constraints follow, for the same reason: a number
 * chosen by a developer is a policy nobody agreed to.
 *
 * <p>A lock is a deliberate trade. It stops an online guessing attack, and it hands anyone who
 * knows a sign-in name a way to keep its owner out. That is recorded as an open question rather
 * than solved here.
 *
 * @param threshold consecutive failures that lock the account, at least one
 * @param duration how long the lock lasts, strictly positive
 */
public record LockoutPolicy(int threshold, Duration duration) {

  public LockoutPolicy {
    Objects.requireNonNull(duration, "duration must not be null");
    if (threshold < 1) {
      throw new IllegalArgumentException("A lockout threshold must be at least one failure");
    }
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("A lockout duration must be positive");
    }
  }
}
