package io.github.naimelijah.hrsched.notifications.adapter.out.persistence;

import io.github.naimelijah.hrsched.notifications.application.port.out.NotificationRepository;
import io.github.naimelijah.hrsched.notifications.domain.model.Notification;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationId;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationKind;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing notifications in PostgreSQL.
 *
 * <p>Scoped twice on every read: by tenant, and by whose inbox it is. Both halves matter — the
 * second is what stops a caller reading somebody else's inbox by knowing a notification's identity.
 *
 * <p>That this survives a restart is the point. A notification records that somebody was told, so
 * losing one means a person was never told while the system believes they were (ADR-021).
 *
 * <p>{@code occurred_at} is written from the value the aggregate carries, never from the database's
 * own clock: when something happened is an input the caller supplies.
 *
 * <p>Notifications raised at one instant are listed by {@code sequence}, the order the database has
 * inserted them in since V13, because a publication tells in an order that ADR-052 relies on to put
 * an approval on top, and an identity is random. Rows stored before V13 were numbered in no
 * recorded order. The column never leaves this class.
 */
@Repository
public class JdbcNotificationRepository implements NotificationRepository {

  private final JdbcClient jdbc;

  /**
   * @param jdbc the client every statement runs on
   * @throws NullPointerException if {@code jdbc} is null
   */
  public JdbcNotificationRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void save(Notification notification) {
    int stored =
        jdbc.sql(
                """
            INSERT INTO notification (id, organisation_id, recipient_id, kind, subject,
                                      occurred_at, read)
            VALUES (:id, :organisation, :recipient, :kind, :subject, :occurredAt, :read)
            ON CONFLICT (id) DO UPDATE SET read = notification.read OR EXCLUDED.read
            WHERE notification.organisation_id = EXCLUDED.organisation_id
              AND notification.recipient_id = EXCLUDED.recipient_id
              AND notification.kind = EXCLUDED.kind
              AND notification.subject = EXCLUDED.subject
              AND notification.occurred_at = EXCLUDED.occurred_at
            """)
            .param("id", notification.id().value())
            .param("organisation", notification.organisation().value())
            .param("recipient", notification.recipient().value())
            .param("kind", notification.kind().name())
            .param("subject", notification.subject())
            .param("occurredAt", notification.occurredAt().atOffset(ZoneOffset.UTC))
            .param("read", notification.isRead())
            .update();
    requireStored(stored, notification.id().value());
  }

  @Override
  public Optional<Notification> findById(
      OrganisationId organisation, EmployeeId recipient, NotificationId id) {
    return jdbc.sql(
            """
            SELECT id, organisation_id, recipient_id, kind, subject, occurred_at, read
            FROM notification
            WHERE organisation_id = :organisation AND recipient_id = :recipient AND id = :id
            """)
        .param("organisation", organisation.value())
        .param("recipient", recipient.value())
        .param("id", id.value())
        .query(JdbcNotificationRepository::toNotification)
        .optional();
  }

  @Override
  public List<Notification> findFor(OrganisationId organisation, EmployeeId recipient) {
    return jdbc.sql(
            """
            SELECT id, organisation_id, recipient_id, kind, subject, occurred_at, read
            FROM notification
            WHERE organisation_id = :organisation AND recipient_id = :recipient
            ORDER BY occurred_at DESC, sequence DESC
            """)
        .param("organisation", organisation.value())
        .param("recipient", recipient.value())
        .query(JdbcNotificationRepository::toNotification)
        .list();
  }

  /**
   * Rebuilds through {@code markedRead} rather than a constructor taking the flag, because that is
   * the only transition the aggregate offers and it is idempotent.
   */
  private static Notification toNotification(ResultSet row, int rowNumber) throws SQLException {
    Notification unread =
        new Notification(
            new NotificationId(row.getObject("id", UUID.class)),
            new OrganisationId(row.getObject("organisation_id", UUID.class)),
            new EmployeeId(row.getObject("recipient_id", UUID.class)),
            NotificationKind.valueOf(row.getString("kind")),
            row.getObject("subject", UUID.class),
            row.getObject("occurred_at", OffsetDateTime.class).toInstant());
    return row.getBoolean("read") ? unread.markedRead() : unread;
  }

  /**
   * A save carries no {@code OrganisationId}, so the upsert's {@code WHERE} is the only thing
   * standing between a re-used identity and another tenant's row, or another person's inbox. It
   * makes the mismatched case affect nothing rather than overwrite; this turns that silence into a
   * refusal, because a write that quietly did nothing is the worse of the two failures.
   *
   * <p>The same statement only ever sets {@code read}, never clears it: being marked read is the
   * one change the domain makes, so an unread copy saved later is stale, not a change. Its {@code
   * WHERE} also requires the kind, subject and moment to be the stored ones, because they are what
   * somebody was told: a save changing one touches no row and is refused here, as a change of
   * recipient is.
   */
  private static void requireStored(int rows, UUID id) {
    if (rows == 0) {
      throw new IllegalStateException(
          ("Refused to store %s: that identity already belongs to another organisation or"
                  + " recipient, or records another kind, subject or moment")
              .formatted(id));
    }
  }
}
