package io.github.naimelijah.hrsched.notifications.adapter.in.web;

import io.github.naimelijah.hrsched.notifications.application.port.in.PublishedNotification;
import java.time.Instant;
import java.util.UUID;

/**
 * One notification in the caller's own inbox.
 *
 * @param id which notification
 * @param kind what happened, as the name of a {@code NotifiableEvent}
 * @param subject the identifier of the thing it happened to, for the caller to resolve
 * @param occurredAt when it happened
 * @param read whether they have already seen it
 */
public record NotificationResponse(
    UUID id, String kind, UUID subject, Instant occurredAt, boolean read) {

  static NotificationResponse from(PublishedNotification notification) {
    return new NotificationResponse(
        notification.id().value(),
        notification.kind().name(),
        notification.subject(),
        notification.occurredAt(),
        notification.read());
  }
}
