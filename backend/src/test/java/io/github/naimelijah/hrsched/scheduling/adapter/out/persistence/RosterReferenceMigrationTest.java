package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What V14 does to a database already holding a reference it now refuses.
 *
 * <p>The suite's own database is always fully migrated, so it can never show what a migration does
 * to rows already there. This test makes a database of its own in the shared container, migrates it
 * only as far as V13, stores a swap request naming work its roster does not hold, as V13 let a
 * writer that skipped the domain store one, and then lets the rest run.
 *
 * <p>V14 fails over it, deliberately: which assignment the request meant is not something a
 * migration can decide.
 *
 * <p>The instants are invented; any would do.
 */
class RosterReferenceMigrationTest {

  /** The last version before the schema held what a swap request names. */
  private static final String BEFORE_REFERENCES_WERE_HELD = "13";

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OffsetDateTime MONDAY = OffsetDateTime.parse("2026-09-07T00:00:00Z");
  private static final OffsetDateTime NEXT_MONDAY = OffsetDateTime.parse("2026-09-14T00:00:00Z");
  private static final OffsetDateTime PUBLISHED = OffsetDateTime.parse("2026-09-01T09:00:00Z");
  private static final OffsetDateTime RAISED = OffsetDateTime.parse("2026-09-02T09:00:00Z");

  private String database;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;

  @BeforeEach
  void storeTheWayV13Did() {
    // A database name cannot be a bind parameter. This one is made here from a UUID, never from
    // anything a caller supplied, and CREATE DATABASE cannot run inside a transaction, which the
    // shared client's autocommit connection is not in.
    database = "roster_references_" + UUID.randomUUID().toString().replace("-", "");
    PostgresDatabase.jdbcClient().sql("CREATE DATABASE " + database).update();
    dataSource = dataSourceFor(database);
    jdbc = JdbcClient.create(dataSource);
    Flyway.configure().dataSource(dataSource).target(BEFORE_REFERENCES_WERE_HELD).load().migrate();

    jdbc.sql("INSERT INTO organisation (id, name) VALUES (:id, 'Acme')")
        .param("id", ACME.value())
        .update();
  }

  @AfterEach
  void dropTheDatabase() {
    dataSource.close();
    PostgresDatabase.jdbcClient().sql("DROP DATABASE " + database + " WITH (FORCE)").update();
  }

  @Test
  void
      GivenARequestNamingNoAssignmentOfItsRoster_WhenMigrated_ThenTheMigrationFailsNamingTheRule() {
    UUID roster = publishedRoster();
    requestNamingNobodysWorkOn(roster);

    assertThatThrownBy(this::migrateToTheLatest)
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("swap_request_names_an_assignment_on_its_roster");
  }

  private void migrateToTheLatest() {
    Flyway.configure().dataSource(dataSource).load().migrate();
  }

  /** A published roster of ours over the week, holding no assignment at all. */
  private UUID publishedRoster() {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO roster (id, organisation_id, starts_at, ends_at, published_at,
                                validated_against_constraint_version)
            VALUES (:id, :organisation, :startsAt, :endsAt, :publishedAt, 1)
            """)
        .param("id", id)
        .param("organisation", ACME.value())
        .param("startsAt", MONDAY)
        .param("endsAt", NEXT_MONDAY)
        .param("publishedAt", PUBLISHED)
        .update();
    return id;
  }

  /** A pending request of ours on that roster, for a shift and a requester it does not hold. */
  private void requestNamingNobodysWorkOn(UUID roster) {
    jdbc.sql(
            """
            INSERT INTO swap_request (id, organisation_id, roster_id, shift_id, requester_id,
                                      counterparty_id, status, requested_at)
            VALUES (:id, :organisation, :roster, :shift, :requester, :counterparty, 'REQUESTED',
                    :requestedAt)
            """)
        .param("id", UUID.randomUUID())
        .param("organisation", ACME.value())
        .param("roster", roster)
        .param("shift", UUID.randomUUID())
        .param("requester", UUID.randomUUID())
        .param("counterparty", UUID.randomUUID())
        .param("requestedAt", RAISED)
        .update();
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
