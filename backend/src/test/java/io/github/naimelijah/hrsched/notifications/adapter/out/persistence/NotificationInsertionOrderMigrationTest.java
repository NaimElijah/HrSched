package io.github.naimelijah.hrsched.notifications.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.naimelijah.hrsched.notifications.domain.model.Notification;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationId;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationKind;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What V13 does to notifications stored before the inbox remembered the order they were raised in
 * (#254).
 *
 * <p>The suite's own database is always fully migrated, so it can never show what a migration does
 * to rows already there. This test makes a database of its own in the shared container, migrates it
 * only as far as V12, stores notifications the way V12 stored them, and then lets the rest run.
 *
 * <p>The instants and identities are invented.
 */
class NotificationInsertionOrderMigrationTest {

  /** The last version before a notification carried the order it was inserted in. */
  private static final String BEFORE_THE_ORDER_WAS_KEPT = "12";

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final EmployeeId ADA = EmployeeId.newId();

  /** Invented. */
  private static final Instant MORNING = Instant.parse("2026-10-05T09:00:00Z");

  /** Invented. */
  private static final Instant NOON = Instant.parse("2026-10-05T12:00:00Z");

  private String database;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;

  @BeforeEach
  void prepareADatabaseAtV12() {
    // A database name cannot be a bind parameter. This one is made here from a UUID, never from
    // anything a caller supplied, and CREATE DATABASE cannot run inside a transaction, which the
    // shared client's autocommit connection is not in.
    database = "notification_order_" + UUID.randomUUID().toString().replace("-", "");
    PostgresDatabase.jdbcClient().sql("CREATE DATABASE " + database).update();
    dataSource = dataSourceFor(database);
    jdbc = JdbcClient.create(dataSource);
    Flyway.configure().dataSource(dataSource).target(BEFORE_THE_ORDER_WAS_KEPT).load().migrate();

    jdbc.sql("INSERT INTO organisation (id, name) VALUES (:id, 'Acme')")
        .param("id", ACME.value())
        .update();
  }

  @AfterEach
  void dropTheDatabase() {
    dataSource.close();
    PostgresDatabase.jdbcClient().sql("DROP DATABASE " + database + " WITH (FORCE)").update();
  }

  /** The column is NOT NULL, so the rows already there must each be given a value, not refused. */
  @Test
  void GivenNotificationsStoredBeforeV13_WhenMigrated_ThenEachIsStillInItsInboxNewestFirst() {
    NotificationId morning = storedTheWayV12Did(MORNING, "00000000-0000-0000-0000-000000000001");
    NotificationId noon = storedTheWayV12Did(NOON, "00000000-0000-0000-0000-000000000002");

    migrateToTheLatest();

    assertThat(new JdbcNotificationRepository(jdbc).findFor(ACME, ADA))
        .extracting(Notification::id)
        .containsExactly(noon, morning);
  }

  /**
   * Every row the migration numbers is numbered below every row raised after it, so something told
   * after the migration, at the very instant of something told before it, is the one on top. The
   * invented identities put the old one first by identity, so an identity tie-break would show.
   */
  @Test
  void GivenANotificationStoredBeforeV13_WhenOneIsRaisedAtTheSameInstantAfter_ThenItComesFirst() {
    NotificationId storedBefore = storedTheWayV12Did(NOON, "00000000-0000-0000-0000-000000000001");

    migrateToTheLatest();
    JdbcNotificationRepository notifications = new JdbcNotificationRepository(jdbc);
    Notification raisedAfter =
        new Notification(
            new NotificationId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
            ACME,
            ADA,
            NotificationKind.SWAP_APPROVED,
            UUID.randomUUID(),
            NOON);
    notifications.save(raisedAfter);

    assertThat(notifications.findFor(ACME, ADA))
        .extracting(Notification::id)
        .containsExactly(raisedAfter.id(), storedBefore);
  }

  private void migrateToTheLatest() {
    Flyway.configure().dataSource(dataSource).load().migrate();
  }

  /** Stores an unread notification for Ada with the columns V12 knew of. */
  private NotificationId storedTheWayV12Did(Instant occurredAt, String id) {
    NotificationId notification = new NotificationId(UUID.fromString(id));
    jdbc.sql(
            """
            INSERT INTO notification (id, organisation_id, recipient_id, kind, subject,
                                      occurred_at, read)
            VALUES (:id, :organisation, :recipient, 'SWAP_LAPSED', :subject, :occurredAt, false)
            """)
        .param("id", notification.value())
        .param("organisation", ACME.value())
        .param("recipient", ADA.value())
        .param("subject", UUID.randomUUID())
        .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
        .update();
    return notification;
  }

  private static HikariDataSource dataSourceFor(String database) {
    PostgreSQLContainer container = PostgresDatabase.container();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(
        "jdbc:postgresql://%s:%d/%s"
            .formatted(
                container.getHost(),
                container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                database));
    config.setUsername(container.getUsername());
    config.setPassword(container.getPassword());
    return new HikariDataSource(config);
  }
}
