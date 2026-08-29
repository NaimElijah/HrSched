package io.github.naimelijah.hrsched.workforce.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of an {@link Employee}.
 *
 * <p>A value object, mirroring {@code ShiftId} in the scheduling context. The two are separate
 * types on purpose: a bare {@link UUID} would let an employee identity be passed where a shift
 * identity is expected, and the compiler could not object.
 *
 * @param value the underlying identifier, never {@code null}
 */
public record EmployeeId(UUID value) {

  public EmployeeId {
    Objects.requireNonNull(value, "value must not be null");
  }

  /**
   * Creates a new, previously unused identity.
   *
   * @return a fresh identity backed by a random UUID
   */
  public static EmployeeId newId() {
    return new EmployeeId(UUID.randomUUID());
  }
}
