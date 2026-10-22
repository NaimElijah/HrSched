package io.github.naimelijah.hrsched.scheduling.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a {@link RosterGeneration}.
 *
 * @param value the underlying identifier, never {@code null}
 */
public record GenerationId(UUID value) {

  public GenerationId {
    Objects.requireNonNull(value, "value must not be null");
  }

  /**
   * @return a fresh identity backed by a random UUID
   */
  public static GenerationId newId() {
    return new GenerationId(UUID.randomUUID());
  }
}
