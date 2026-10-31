package io.github.naimelijah.hrsched.shared.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of an {@code Organisation}, and the tenant boundary of the whole system.
 *
 * <p>This type appears on every aggregate in every context. A value carrying it is owned by exactly
 * one organisation, and an operation that mixes two of them is a data leak between paying customers
 * rather than an ordinary bug.
 *
 * @param value the underlying identifier, never {@code null}
 */
public record OrganisationId(UUID value) {

  public OrganisationId {
    Objects.requireNonNull(value, "value must not be null");
  }

  /**
   * Creates a new, previously unused identity.
   *
   * @return a fresh identity backed by a random UUID
   */
  public static OrganisationId newId() {
    return new OrganisationId(UUID.randomUUID());
  }
}
