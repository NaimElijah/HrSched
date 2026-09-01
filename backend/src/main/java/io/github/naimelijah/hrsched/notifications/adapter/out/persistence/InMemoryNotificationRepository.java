package io.github.naimelijah.hrsched.notifications.adapter.out.persistence;

import io.github.naimelijah.hrsched.notifications.application.port.out.NotificationRepository;
import io.github.naimelijah.hrsched.notifications.domain.model.Notification;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing notifications in memory.
 *
 * <p>PLACEHOLDER until ADR-004, scoped by organisation and by recipient on every read.
 *
 * <p>Newest first, because an inbox nobody can find the new thing in is not an inbox.
 */
@Repository
public class InMemoryNotificationRepository implements NotificationRepository {

  private final Map<NotificationId, Notification> byId = new ConcurrentHashMap<>();

  @Override
  public void save(Notification notification) {
    byId.put(notification.id(), notification);
  }

  @Override
  public Optional<Notification> findById(
      OrganisationId organisation, EmployeeId recipient, NotificationId id) {
    return Optional.ofNullable(byId.get(id)).filter(mine(organisation, recipient));
  }

  @Override
  public List<Notification> findFor(OrganisationId organisation, EmployeeId recipient) {
    return byId.values().stream()
        .filter(mine(organisation, recipient))
        .sorted(Comparator.comparing(Notification::occurredAt).reversed())
        .toList();
  }

  /** Both halves of the predicate matter: the tenant, and whose inbox it is. */
  private static java.util.function.Predicate<Notification> mine(
      OrganisationId organisation, EmployeeId recipient) {
    return notification ->
        notification.organisation().equals(organisation) && notification.isFor(recipient);
  }
}
