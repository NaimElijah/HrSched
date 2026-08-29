package io.github.naimelijah.hrsched.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a {@link Shift}.
 *
 * <p>A value object: two instances wrapping the same {@link UUID} are the same identity. Wrapping
 * the raw UUID keeps a shift identity from being passed where an employee identity is expected,
 * which the compiler cannot catch if both are bare UUIDs.
 *
 * @param value the underlying identifier, never {@code null}
 */
public record ShiftId(UUID value) {

  public ShiftId {
    Objects.requireNonNull(value, "value must not be null");
  }

  /**
   * Creates a new, previously unused identity.
   *
   * @return a fresh identity backed by a random UUID
   */
  public static ShiftId newId() {
    return new ShiftId(UUID.randomUUID());
  }
}
