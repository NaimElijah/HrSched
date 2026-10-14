package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V6 carries the rosters stored before history was kept across without losing any of them (R37).
 *
 * <p>The suite's own database is always fully migrated, so it can never show what a migration does
 * to rows already there. This test makes a database of its own in the shared container, migrates it
 * only as far as V5, stores rosters the way V5 stored them, and then lets V6 run over them.
 */
class AppendOnlyRosterHistoryMigrationTest {

  /** The last version that stored a roster's status in place. */
  private static final String BEFORE_HISTORY_WAS_KEPT = "5";

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OffsetDateTime MONDAY = OffsetDateTime.parse("2026-09-07T00:00:00Z");
  private static final OffsetDateTime NEXT_MONDAY = OffsetDateTime.parse("2026-09-14T00:00:00Z");

  private final RosterId draft = RosterId.newId();
  private final RosterId published = RosterId.newId();
  private final RosterId superseded = RosterId.newId();
  private final Assignment ada = new Assignment(ShiftId.newId(), EmployeeId.newId());
  private final Assignment grace = new Assignment(ShiftId.newId(), EmployeeId.newId());

  private String database;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;

  @BeforeEach
  void storeRostersTheWayV5Did() {
    // A database name cannot be a bind parameter. This one is made here from a UUID, never from
    // anything a caller supplied, and CREATE DATABASE cannot run inside a transaction, which the
    // shared client's autocommit connection is not in.
    database = "roster_history_" + UUID.randomUUID().toString().replace("-", "");
    PostgresDatabase.jdbcClient().sql("CREATE DATABASE " + database).update();
    dataSource = dataSourceFor(database);
    jdbc = JdbcClient.create(dataSource);
    Flyway.configure().dataSource(dataSource).target(BEFORE_HISTORY_WAS_KEPT).load().migrate();

    jdbc.sql("INSERT INTO organisation (id, name) VALUES (:id, 'Acme')")
        .param("id", ACME.value())
        .update();
    storedInPlace(draft, "DRAFT", null);
    storedInPlace(published, "PUBLISHED", 3);
    storedInPlace(superseded, "SUPERSEDED", 2);
    assigned(published, ada);
    assigned(superseded, grace);
  }

  @AfterEach
  void dropTheDatabase() {
    dataSource.close();
    PostgresDatabase.jdbcClient().sql("DROP DATABASE " + database + " WITH (FORCE)").update();
  }

  @Test
  void GivenRostersStoredInPlace_WhenMigrated_ThenEveryRosterKeepsItsStatusVersionAndAssignments() {
    migrateToTheLatest();

    JdbcRosterRepository rosters = new JdbcRosterRepository(jdbc);
    Roster stillADraft = rosters.findById(ACME, draft).orElseThrow();
    Roster stillPublished = rosters.findById(ACME, published).orElseThrow();
    Roster stillSuperseded = rosters.findById(ACME, superseded).orElseThrow();

    assertThat(stillADraft.status()).isEqualTo(RosterStatus.DRAFT);
    assertThat(stillADraft.validatedAgainstConstraintVersion()).isNull();
    assertThat(stillPublished.status()).isEqualTo(RosterStatus.PUBLISHED);
    assertThat(stillPublished.validatedAgainstConstraintVersion()).isEqualTo(3);
    assertThat(stillPublished.assignments()).containsExactly(ada);
    assertThat(stillSuperseded.status()).isEqualTo(RosterStatus.SUPERSEDED);
    assertThat(stillSuperseded.validatedAgainstConstraintVersion()).isEqualTo(2);
    assertThat(stillSuperseded.assignments()).containsExactly(grace);
  }

  /**
   * Nothing recorded when the stored rosters were published, so none is invented: each is given the
   * one moment history began to be kept, from which a published roster governs.
   */
  @Test
  void
      GivenPublishedAndSupersededRosters_WhenMigrated_ThenEachIsRecordedFromTheOneMomentHistoryBegan() {
    migrateToTheLatest();

    JdbcRosterRepository rosters = new JdbcRosterRepository(jdbc);
    Roster stillADraft = rosters.findById(ACME, draft).orElseThrow();
    Roster stillPublished = rosters.findById(ACME, published).orElseThrow();
    Roster stillSuperseded = rosters.findById(ACME, superseded).orElseThrow();

    assertThat(stillADraft.publishedAt()).isNull();
    assertThat(stillPublished.publishedAt()).isNotNull();
    assertThat(stillPublished.supersededAt()).isNull();
    assertThat(stillSuperseded.publishedAt()).isEqualTo(stillPublished.publishedAt());
  }

  /**
   * When a stored roster was superseded is as unknown as when it was published. An empty interval
   * is the honest record of that: it claims the roster governed at no recorded moment, rather than
   * claiming it governed beside the roster that replaced it.
   */
  @Test
  void GivenASupersededRoster_WhenMigrated_ThenItClaimsToHaveGovernedAtNoRecordedMoment() {
    migrateToTheLatest();

    Roster stillSuperseded =
        new JdbcRosterRepository(jdbc).findById(ACME, superseded).orElseThrow();

    assertThat(stillSuperseded.supersededAt()).isEqualTo(stillSuperseded.publishedAt());
  }

  private void migrateToTheLatest() {
    Flyway.configure().dataSource(dataSource).load().migrate();
  }

  /** Stores a roster over the whole week, as V5 did: with a status, and no moments of record. */
  private void storedInPlace(RosterId id, String status, Integer version) {
    jdbc.sql(
            """
            INSERT INTO roster (id, organisation_id, starts_at, ends_at, status,
                                validated_against_constraint_version)
            VALUES (:id, :organisation, :startsAt, :endsAt, :status, :version)
            """)
        .param("id", id.value())
        .param("organisation", ACME.value())
        .param("startsAt", MONDAY)
        .param("endsAt", NEXT_MONDAY)
        .param("status", status)
        .param("version", version)
        .update();
  }

  private void assigned(RosterId roster, Assignment assignment) {
    jdbc.sql(
            """
            INSERT INTO roster_assignment (roster_id, shift_id, employee_id)
            VALUES (:roster, :shift, :employee)
            """)
        .param("roster", roster.value())
        .param("shift", assignment.shift().value())
        .param("employee", assignment.employee().value())
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
