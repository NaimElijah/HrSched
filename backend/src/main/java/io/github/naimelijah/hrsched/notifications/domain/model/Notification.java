package io.github.naimelijah.hrsched.notifications.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Something one person has been told, and whether they have seen it.
 *
 * <p>An entity: two notifications of the same kind about the same subject on the same day are still
 * separate things somebody has to acknowledge, so equality is by {@link NotificationId} alone.
 *
 * <p>Immutable, like every other aggregate here. Marking one read returns a new instance.
 *
 * <p><strong>It carries identifiers, a kind and a time, and nothing else.</strong> There is no
 * message field, no title, and no name. That is the same guarantee {@code AuditedChange} makes and
 * it is made the same way — by having nowhere to put such a thing — because a notification store is
 * a second database of who did what to whom, and a free-text field is how it would quietly become a
 * copy of the roster with people's names in it. Whoever reads a notification resolves the
 * identifiers themselves.
 */
public final class Notification {

  private final NotificationId id;
  private final OrganisationId organisation;
  private final EmployeeId recipient;
  private final NotificationKind kind;
  private final UUID subject;
  private final Instant occurredAt;
  private final boolean read;

  /**
   * @param id the notification's identity
   * @param organisation whose it is; a notification never crosses a tenant boundary
   * @param recipient the personnel record it is addressed to
   * @param kind what happened
   * @param subject the identifier of the thing it happened to
   * @param occurredAt when it happened, supplied by the caller's clock rather than read from one
   * @throws NullPointerException if any argument is null
   */
  public Notification(
      NotificationId id,
      OrganisationId organisation,
      EmployeeId recipient,
      NotificationKind kind,
      UUID subject,
      Instant occurredAt) {
    this(id, organisation, recipient, kind, subject, occurredAt, false);
  }

  private Notification(
      NotificationId id,
      OrganisationId organisation,
      EmployeeId recipient,
      NotificationKind kind,
      UUID subject,
      Instant occurredAt,
      boolean read) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organisation = Objects.requireNonNull(organisation, "organisation must not be null");
    this.recipient = Objects.requireNonNull(recipient, "recipient must not be null");
    this.kind = Objects.requireNonNull(kind, "kind must not be null");
    this.subject = Objects.requireNonNull(subject, "subject must not be null");
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    this.read = read;
  }

  /**
   * @return this notification marked as seen; already-read is not an error, because acknowledging
   *     twice is what a reader with two tabs open does
   */
  public Notification markedRead() {
    return new Notification(id, organisation, recipient, kind, subject, occurredAt, true);
  }

  /**
   * @param employee somebody who might be its recipient
   * @return whether this notification is addressed to them
   */
  public boolean isFor(EmployeeId employee) {
    Objects.requireNonNull(employee, "employee must not be null");
    return recipient.equals(employee);
  }

  public NotificationId id() {
    return id;
  }

  public OrganisationId organisation() {
    return organisation;
  }

  public EmployeeId recipient() {
    return recipient;
  }

  public NotificationKind kind() {
    return kind;
  }

  public UUID subject() {
    return subject;
  }

  public Instant occurredAt() {
    return occurredAt;
  }

  public boolean isRead() {
    return read;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Notification notification && id.equals(notification.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  /** Names no recipient and no subject: a {@code toString} ends up in places a field does not. */
  @Override
  public String toString() {
    return "Notification[id=%s, kind=%s, read=%s]".formatted(id, kind, read);
  }
}
