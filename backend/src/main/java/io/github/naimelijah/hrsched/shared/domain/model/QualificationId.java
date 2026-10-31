package io.github.naimelijah.hrsched.shared.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a qualification.
 *
 * <p>In the kernel because it crosses contexts: workforce owns what a qualification <em>is</em>,
 * while scheduling records which ones a shift requires. Contexts refer to one another's concepts by
 * identity, never by entity.
 *
 * @param value the underlying identifier, never {@code null}
 */
public record QualificationId(UUID value) {

  public QualificationId {
    Objects.requireNonNull(value, "value must not be null");
  }

  /**
   * Creates a new, previously unused identity.
   *
   * @return a fresh identity backed by a random UUID
   */
  public static QualificationId newId() {
    return new QualificationId(UUID.randomUUID());
  }
}
