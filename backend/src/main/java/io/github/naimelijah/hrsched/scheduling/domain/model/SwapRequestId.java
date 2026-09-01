package io.github.naimelijah.hrsched.scheduling.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a {@link SwapRequest}.
 *
 * @param value the underlying identifier, never {@code null}
 */
public record SwapRequestId(UUID value) {

  public SwapRequestId {
    Objects.requireNonNull(value, "value must not be null");
  }

  /**
   * @return a fresh identity backed by a random UUID
   */
  public static SwapRequestId newId() {
    return new SwapRequestId(UUID.randomUUID());
  }
}
