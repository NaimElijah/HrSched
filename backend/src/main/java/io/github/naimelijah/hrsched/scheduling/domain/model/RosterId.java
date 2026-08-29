package io.github.naimelijah.hrsched.scheduling.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a {@link Roster}.
 *
 * @param value the underlying identifier, never {@code null}
 */
public record RosterId(UUID value) {

  public RosterId {
    Objects.requireNonNull(value, "value must not be null");
  }

  /**
   * @return a fresh identity backed by a random UUID
   */
  public static RosterId newId() {
    return new RosterId(UUID.randomUUID());
  }
}
