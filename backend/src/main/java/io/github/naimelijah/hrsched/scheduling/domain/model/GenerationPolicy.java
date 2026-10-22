package io.github.naimelijah.hrsched.scheduling.domain.model;

import java.time.Duration;
import java.util.Objects;

/**
 * How long a generation may stay pending before it is reported lost (ADR-046).
 *
 * <p>Configuration, built by the composition root, so that it can be set against how long a solve
 * is allowed to run. It must exceed that, or a solve still running is reported lost, and the
 * generator refuses one that its own searches could outlast.
 *
 * @param lostAfter how long after it is asked for a generation still pending counts as lost
 * @throws IllegalArgumentException if it is not positive
 */
public record GenerationPolicy(Duration lostAfter) {

  public GenerationPolicy {
    Objects.requireNonNull(lostAfter, "lostAfter must not be null");
    if (lostAfter.isNegative() || lostAfter.isZero()) {
      throw new IllegalArgumentException("lostAfter must be positive, but is " + lostAfter);
    }
  }
}
