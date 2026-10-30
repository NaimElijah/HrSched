package io.github.naimelijah.hrsched.shared.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of an {@code Employee}.
 *
 * <p>In the kernel because it crosses contexts: workforce owns the employee, while scheduling
 * records which employee an assignment is for. A bare {@link UUID} would let an employee identity
 * be passed where a shift identity is expected, and the compiler could not object.
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
