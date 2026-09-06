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
 */
@Repository
public class JdbcNotificationRepository implements NotificationRepository {

  private final JdbcClient jdbc;

  public JdbcNotificationRepository(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
  }

  @Override
  public void save(Notification notification) {
    jdbc.sql(
            """
            INSERT INTO notification (id, organisation_id, recipient_id, kind, subject,
                                      occurred_at, read)
            VALUES (:id, :organisation, :recipient, :kind, :subject, :occurredAt, :read)
            ON CONFLICT (id) DO UPDATE SET organisation_id = EXCLUDED.organisation_id,
                                           recipient_id = EXCLUDED.recipient_id,
                                           kind = EXCLUDED.kind,
                                           subject = EXCLUDED.subject,
                                           occurred_at = EXCLUDED.occurred_at,
                                           read = EXCLUDED.read
            """)
        .param("id", notification.id().value())
        .param("organisation", notification.organisation().value())
        .param("recipient", notification.recipient().value())
        .param("kind", notification.kind().name())
        .param("subject", notification.subject())
        .param("occurredAt", notification.occurredAt().atOffset(ZoneOffset.UTC))
        .param("read", notification.isRead())
        .update();
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
            ORDER BY occurred_at DESC, id
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
}
