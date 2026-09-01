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

  void save(Notification notification);

  Optional<Notification> findById(
      OrganisationId organisation, EmployeeId recipient, NotificationId id);

  /**
   * @param organisation whose notifications
   * @param recipient whose inbox
   * @return their notifications, newest first
   */
  List<Notification> findFor(OrganisationId organisation, EmployeeId recipient);
}
