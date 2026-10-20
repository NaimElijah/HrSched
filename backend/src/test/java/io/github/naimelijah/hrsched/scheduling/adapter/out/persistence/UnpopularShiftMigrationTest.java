package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What V10 does to shifts stored before a shift could be marked unpopular (ADR-010).
 *
 * <p>The suite's own database is always fully migrated, so it can never show what a migration does
 * to rows already there. This test makes a database of its own in the shared container, migrates it
 * only as far as V9, stores a shift the way V9 stored one, and then lets the rest run.
 */
class UnpopularShiftMigrationTest {

  /** The last version before a shift carried the mark. */
  private static final String BEFORE_THE_MARK = "9";

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OffsetDateTime MONDAY_EIGHT = OffsetDateTime.parse("2026-09-07T08:00:00Z");
  private static final OffsetDateTime MONDAY_FOUR = OffsetDateTime.parse("2026-09-07T16:00:00Z");

  private String database;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;

  @BeforeEach
  void storeTheWayV9Did() {
    // A database name cannot be a bind parameter. This one is made here from a UUID, never from
    // anything a caller supplied, and CREATE DATABASE cannot run inside a transaction, which the
    // shared client's autocommit connection is not in.
    database = "unpopular_shifts_" + UUID.randomUUID().toString().replace("-", "");
    PostgresDatabase.jdbcClient().sql("CREATE DATABASE " + database).update();
    dataSource = dataSourceFor(database);
    jdbc = JdbcClient.create(dataSource);
    Flyway.configure().dataSource(dataSource).target(BEFORE_THE_MARK).load().migrate();

    jdbc.sql("INSERT INTO organisation (id, name) VALUES (:id, 'Acme')")
        .param("id", ACME.value())
        .update();
  }

  @AfterEach
  void dropTheDatabase() {
    dataSource.close();
    PostgresDatabase.jdbcClient().sql("DROP DATABASE " + database + " WITH (FORCE)").update();
  }

  /** Nobody marked it, so it is stored as nobody marked it: not unpopular. */
  @Test
  void GivenAShiftStoredBeforeTheMark_WhenMigrated_ThenItIsNotUnpopular() {
    ShiftId stored = shiftStoredTheWayV9Did();

    migrateToTheLatest();

    assertThat(new JdbcShiftRepository(jdbc).findById(ACME, stored))
        .get()
        .extracting(Shift::unpopular)
        .isEqualTo(false);
  }

  /**
   * The default only fills the rows already there. A later writer that leaves the mark out is
   * refused, so whether a shift is unpopular is always somebody's answer, never the schema's.
   */
  @Test
  void GivenTheMigratedSchema_WhenAShiftIsStoredWithoutTheMark_ThenItIsRefused() {
    migrateToTheLatest();

    assertThatThrownBy(this::shiftStoredTheWayV9Did)
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private void migrateToTheLatest() {
    Flyway.configure().dataSource(dataSource).load().migrate();
  }

  /** Stores an eight-hour shift with the columns V9 knew of, and no mark. */
  private ShiftId shiftStoredTheWayV9Did() {
    ShiftId id = ShiftId.newId();
    jdbc.sql(
            """
            INSERT INTO shift (id, organisation_id, starts_at, ends_at, zone, required_headcount)
            VALUES (:id, :organisation, :startsAt, :endsAt, 'UTC', 1)
            """)
        .param("id", id.value())
        .param("organisation", ACME.value())
        .param("startsAt", MONDAY_EIGHT)
        .param("endsAt", MONDAY_FOUR)
        .update();
    return id;
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
