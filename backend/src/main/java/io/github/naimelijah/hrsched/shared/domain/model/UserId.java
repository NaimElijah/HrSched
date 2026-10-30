package io.github.naimelijah.hrsched.shared.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of an {@code Account}.
 *
 * <p>Distinct from an employee identity on purpose: not every user is rostered, and not every
 * rostered person necessarily logs in.
 *
 * @param value the underlying identifier, never {@code null}
 */
public record UserId(UUID value) {

  public UserId {
    Objects.requireNonNull(value, "value must not be null");
  }

  /**
   * @return a fresh identity backed by a random UUID
   */
  public static UserId newId() {
    return new UserId(UUID.randomUUID());
  }
}
