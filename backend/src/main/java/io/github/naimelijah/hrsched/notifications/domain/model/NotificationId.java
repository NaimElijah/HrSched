package io.github.naimelijah.hrsched.notifications.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a {@link Notification}.
 *
 * @param value the underlying identifier, never {@code null}
 */
public record NotificationId(UUID value) {

  /**
   * @throws NullPointerException if {@code value} is null
   */
  public NotificationId {
    Objects.requireNonNull(value, "value must not be null");
  }

  /**
   * @return a fresh identity backed by a random UUID
   */
  public static NotificationId newId() {
    return new NotificationId(UUID.randomUUID());
  }
}
