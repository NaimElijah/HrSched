package io.github.naimelijah.hrsched.notifications.domain.model;

/**
 * Raised when a notification is named that the caller has none of.
 *
 * <p>Reported as absence rather than as a permission failure, because somebody else's notification
 * and a notification that never existed must be indistinguishable — the same reasoning that makes a
 * cross-tenant attempt a 404 (ADR-009).
 */
public class UnknownNotificationException extends RuntimeException {

  /**
   * @param notification the identity the caller named; it appears in the message, so the message
   *     must not reach a response (ADR-024)
   */
  public UnknownNotificationException(NotificationId notification) {
    super("No such notification %s".formatted(notification.value()));
  }
}
