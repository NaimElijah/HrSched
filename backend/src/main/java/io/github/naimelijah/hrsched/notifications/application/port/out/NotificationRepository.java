package io.github.naimelijah.hrsched.notifications.application.port.out;

import io.github.naimelijah.hrsched.notifications.domain.model.Notification;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.Optional;

/**
 * Driven port: storing notifications.
 *
 * <p>Every lookup is scoped by organisation, so a cross-tenant read cannot be written by forgetting
 * a filter (ADR-009). The recipient is a parameter for the same reason: there is deliberately no
 * {@code findById} that takes a notification identity alone, because such a method is the one an
 * incautious caller would use to hand somebody else's inbox to the wrong reader.
 */
public interface NotificationRepository {

  /**
   * Stores a notification, or marks a stored one read.
   *
   * <p>Saving again what is already stored changes nothing, and saving an unread copy of a stored
   * read notification leaves it read: being marked read is the one change a notification makes, so
   * a stale unread copy is not a change.
   *
   * @param notification what to store; its organisation and recipient are the ones it is stored
   *     under
   * @throws IllegalStateException if its identity is already stored for another organisation or
   *     recipient, or records another kind, subject or moment; nothing is changed
   */
  void save(Notification notification);

  /**
   * Finds one notification in one person's inbox.
   *
   * @param organisation whose notifications
   * @param recipient whose inbox
   * @param id which notification
   * @return the notification, or empty when there is none with that identity in that inbox, whether
   *     it does not exist or belongs to another organisation or recipient
   */
  Optional<Notification> findById(
      OrganisationId organisation, EmployeeId recipient, NotificationId id);

  /**
   * @param organisation whose notifications
   * @param recipient whose inbox
   * @return their notifications, newest first, and of those raised at one instant, the last raised
   *     first; one saved again, marked read, keeps its place
   */
  List<Notification> findFor(OrganisationId organisation, EmployeeId recipient);
}
