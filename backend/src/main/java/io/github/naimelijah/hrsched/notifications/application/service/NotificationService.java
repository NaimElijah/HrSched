package io.github.naimelijah.hrsched.notifications.application.service;

import io.github.naimelijah.hrsched.notifications.application.port.in.NotifiableEvent;
import io.github.naimelijah.hrsched.notifications.application.port.in.NotifyCommand;
import io.github.naimelijah.hrsched.notifications.application.port.in.NotifyUseCase;
import io.github.naimelijah.hrsched.notifications.application.port.in.PublishedNotification;
import io.github.naimelijah.hrsched.notifications.application.port.in.ReadNotificationsUseCase;
import io.github.naimelijah.hrsched.notifications.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.notifications.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.notifications.application.port.out.NotificationChange;
import io.github.naimelijah.hrsched.notifications.application.port.out.NotificationRepository;
import io.github.naimelijah.hrsched.notifications.domain.model.NotAddressableException;
import io.github.naimelijah.hrsched.notifications.domain.model.Notification;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationId;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationKind;
import io.github.naimelijah.hrsched.notifications.domain.model.UnknownNotificationException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Orchestrates the notification use cases.
 *
 * <p>Carries no Spring annotation, so it can be constructed with {@code new} in a test.
 *
 * <p>Takes a {@link Clock} rather than calling {@code Instant.now()}, so that when a notification
 * was raised is an input the tests control instead of an ambient fact they have to tolerate.
 */
public class NotificationService implements NotifyUseCase, ReadNotificationsUseCase {

  private final NotificationRepository notifications;
  private final AuditTrail audit;
  private final Clock clock;

  /**
   * @param notifications where notifications are stored
   * @param audit where each raising and each acknowledgement is recorded
   * @param clock the source of every notification's {@code occurredAt}
   * @throws NullPointerException if any argument is null
   */
  public NotificationService(NotificationRepository notifications, AuditTrail audit, Clock clock) {
    this.notifications = Objects.requireNonNull(notifications, "notifications must not be null");
    this.audit = Objects.requireNonNull(audit, "audit must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Gated by the tenant boundary alone, and no role.
   *
   * <p>An employee asking a colleague for a swap has to be able to tell them, and no role describes
   * "may cause a notification". What keeps that safe is that this port is not on a controller: the
   * only callers are use cases that already decided somebody deserves telling. See {@link
   * NotifyUseCase}.
   *
   * <p>Records {@code NOTIFICATION_RAISED} straight after its own save, inside the caller's
   * transaction. When a caller tells two people and the second store fails, the first row rolls
   * back and its line stays: no call order removes that, only a trail written in the same
   * transaction would (ADR-052, #31).
   */
  @Override
  public NotificationId notify(Actor actor, NotifyCommand command) {
    actor.requireAccessTo(command.organisation());
    Notification notification =
        new Notification(
            NotificationId.newId(),
            command.organisation(),
            command.recipient(),
            kindOf(command.kind()),
            command.subject(),
            clock.instant());
    notifications.save(notification);
    recorded(
        actor, command.organisation(), NotificationChange.NOTIFICATION_RAISED, notification.id());
    return notification.id();
  }

  @Override
  public List<PublishedNotification> findMine(Actor actor, OrganisationId organisation) {
    TenantActor member = actor.requireAccessTo(organisation);
    return notifications.findFor(organisation, inboxOf(member)).stream()
        .map(NotificationService::publish)
        .toList();
  }

  @Override
  public void markRead(Actor actor, OrganisationId organisation, NotificationId id) {
    TenantActor member = actor.requireAccessTo(organisation);
    Notification mine =
        notifications
            .findById(organisation, inboxOf(member), id)
            .orElseThrow(() -> new UnknownNotificationException(id));
    notifications.save(mine.markedRead());
    recorded(actor, organisation, NotificationChange.NOTIFICATION_READ, id);
  }

  /**
   * Whose inbox is being read, taken from the actor and never from a parameter.
   *
   * <p>This is the whole of the access control for reading. There is no role that opens somebody
   * else's inbox because there is nowhere to name one: the recipient is the personnel record the
   * actor carries (ADR-012), so asking for a colleague's notifications is not refused so much as
   * unrepresentable.
   *
   * <p>Takes the member {@code requireAccessTo} handed back, so the platform operator never gets
   * this far: it is refused as no member of any organisation (ADR-030), not as somebody without an
   * inbox.
   */
  private static EmployeeId inboxOf(TenantActor member) {
    return member.employee().orElseThrow(() -> new NotAddressableException(member.id()));
  }

  /**
   * Called after the repository, never before: a line written before a write that can still fail
   * records something that did not happen (ADR-018, ADR-052).
   */
  private void recorded(
      Actor actor, OrganisationId organisation, NotificationChange change, NotificationId subject) {
    audit.record(new AuditedChange(organisation, actor.id(), change, subject.value()));
  }

  /**
   * Translates the published vocabulary into the domain's own; {@link #eventOf} is the reverse.
   *
   * <p>Two enums that agree exactly today, kept apart so that changing one is not automatically a
   * change to every other context. See {@link NotifiableEvent}. Both switches list every value with
   * no default, so a value added to either enum fails to compile here instead of being dropped.
   */
  private static NotificationKind kindOf(NotifiableEvent event) {
    return switch (event) {
      case SWAP_REQUESTED -> NotificationKind.SWAP_REQUESTED;
      case SWAP_APPROVED -> NotificationKind.SWAP_APPROVED;
      case SWAP_REJECTED -> NotificationKind.SWAP_REJECTED;
      case SWAP_LAPSED -> NotificationKind.SWAP_LAPSED;
    };
  }

  private static NotifiableEvent eventOf(NotificationKind kind) {
    return switch (kind) {
      case SWAP_REQUESTED -> NotifiableEvent.SWAP_REQUESTED;
      case SWAP_APPROVED -> NotifiableEvent.SWAP_APPROVED;
      case SWAP_REJECTED -> NotifiableEvent.SWAP_REJECTED;
      case SWAP_LAPSED -> NotifiableEvent.SWAP_LAPSED;
    };
  }

  private static PublishedNotification publish(Notification notification) {
    return new PublishedNotification(
        notification.id(),
        eventOf(notification.kind()),
        notification.subject(),
        notification.occurredAt(),
        notification.isRead());
  }
}
