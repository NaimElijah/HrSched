package io.github.naimelijah.hrsched.notifications.application.port.in;

import io.github.naimelijah.hrsched.notifications.domain.model.NotificationId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a reader is told about one of their notifications.
 *
 * <p>Carries no recipient: every entry belongs to the person asking, and repeating their identity
 * on each row would only invite a client to render somebody else's.
 *
 * @param id which notification
 * @param kind what happened
 * @param subject the identifier of the thing it happened to, for the reader to resolve
 * @param occurredAt when it happened
 * @param read whether they have already seen it
 */
public record PublishedNotification(
    NotificationId id, NotifiableEvent kind, UUID subject, Instant occurredAt, boolean read) {

  public PublishedNotification {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(subject, "subject must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
  }
}
