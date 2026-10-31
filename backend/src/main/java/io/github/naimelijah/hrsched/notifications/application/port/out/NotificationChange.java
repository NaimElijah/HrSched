package io.github.naimelijah.hrsched.notifications.application.port.out;

/**
 * The notification changes worth an audit entry.
 *
 * <p>An entry is a log line, not a stored record (ADR-018).
 *
 * <p>Acknowledging is recorded as well as sending, because "they were told" and "they saw it" are
 * different claims and only the second one is ever disputed.
 */
public enum NotificationChange {

  /** Somebody was told something. */
  NOTIFICATION_RAISED,

  /** Somebody acknowledged having seen it. */
  NOTIFICATION_READ
}
