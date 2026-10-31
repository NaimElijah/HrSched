package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestStatus;
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
 * What the swap migrations do to requests stored before their rules existed.
 *
 * <p>The suite's own database is always fully migrated, so it can never show what a migration does
 * to rows already there. This test makes a database of its own in the shared container, migrates it
 * only as far as V6, stores rosters and requests the way V6 stored them, and then lets the rest
 * run.
 *
 * <p>V7: a request still pending against a roster that was already superseded lapses (R33), as it
 * would have had the rule existed when the roster was replaced. Nobody is told; a migration has no
 * caller to tell anybody on behalf of.
 *
 * <p>V8: of several requests pending for one assignment, the oldest keeps waiting and the rest
 * lapse (R32), so the index that keeps it to one can be built over what was already there.
 */
class SwapRequestMigrationTest {

  /** The last version before a swap request could lapse. */
  private static final String BEFORE_REQUESTS_LAPSED = "6";

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OffsetDateTime MONDAY = OffsetDateTime.parse("2026-09-07T00:00:00Z");
  private static final OffsetDateTime NEXT_MONDAY = OffsetDateTime.parse("2026-09-14T00:00:00Z");
  private static final OffsetDateTime PUBLISHED = OffsetDateTime.parse("2026-09-01T09:00:00Z");
  private static final OffsetDateTime REPLACED = OffsetDateTime.parse("2026-09-03T09:00:00Z");
  private static final OffsetDateTime RAISED = OffsetDateTime.parse("2026-09-02T09:00:00Z");
  private static final OffsetDateTime LATER = OffsetDateTime.parse("2026-09-02T10:00:00Z");

  private final RosterId superseded = RosterId.newId();
  private final RosterId current = RosterId.newId();

  private String database;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;

  @BeforeEach
  void storeTheWayV6Did() {
    // A database name cannot be a bind parameter. This one is made here from a UUID, never from
    // anything a caller supplied, and CREATE DATABASE cannot run inside a transaction, which the
    // shared client's autocommit connection is not in.
    database = "swap_requests_" + UUID.randomUUID().toString().replace("-", "");
    PostgresDatabase.jdbcClient().sql("CREATE DATABASE " + database).update();
    dataSource = dataSourceFor(database);
    jdbc = JdbcClient.create(dataSource);
    Flyway.configure().dataSource(dataSource).target(BEFORE_REQUESTS_LAPSED).load().migrate();

    jdbc.sql("INSERT INTO organisation (id, name) VALUES (:id, 'Acme')")
        .param("id", ACME.value())
        .update();
    // The superseded roster governed until the current one replaced it, and the two only touch.
    roster(superseded, PUBLISHED, REPLACED);
    roster(current, REPLACED, null);
  }

  @AfterEach
  void dropTheDatabase() {
    dataSource.close();
    PostgresDatabase.jdbcClient().sql("DROP DATABASE " + database + " WITH (FORCE)").update();
  }

  @Test
  void GivenARequestPendingOnASupersededRoster_WhenMigrated_ThenItHasLapsed() {
    SwapRequestId request = requestOn(superseded, "REQUESTED");

    migrateToTheLatest();

    assertThat(statusOf(request)).isEqualTo(SwapRequestStatus.LAPSED);
  }

  @Test
  void GivenARequestPendingOnTheCurrentRoster_WhenMigrated_ThenItIsStillPending() {
    SwapRequestId request = requestOn(current, "REQUESTED");

    migrateToTheLatest();

    assertThat(statusOf(request)).isEqualTo(SwapRequestStatus.REQUESTED);
  }

  /** Only a request still waiting lapses: a decision already made is history, and stays as made. */
  @Test
  void GivenADecidedRequestOnASupersededRoster_WhenMigrated_ThenItKeepsItsDecision() {
    SwapRequestId approved = requestOn(superseded, "APPROVED");
    SwapRequestId rejected = requestOn(superseded, "REJECTED");

    migrateToTheLatest();

    assertThat(statusOf(approved)).isEqualTo(SwapRequestStatus.APPROVED);
    assertThat(statusOf(rejected)).isEqualTo(SwapRequestStatus.REJECTED);
  }

  /** The order a Scheduler works through the queue: the first raised is the one still waiting. */
  @Test
  void GivenSeveralRequestsPendingForOneAssignment_WhenMigrated_ThenOnlyTheOldestIsStillPending() {
    UUID shift = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    SwapRequestId later = requestOn(current, shift, requester, "REQUESTED", LATER);
    SwapRequestId oldest = requestOn(current, shift, requester, "REQUESTED", RAISED);

    migrateToTheLatest();

    assertThat(statusOf(oldest)).isEqualTo(SwapRequestStatus.REQUESTED);
    assertThat(statusOf(later)).isEqualTo(SwapRequestStatus.LAPSED);
  }

  /**
   * Two raised in the same instant still need one to keep, and the identity breaks the tie as it
   * does in the queue: PostgreSQL orders a {@code uuid} as unsigned bytes, which is not {@code
   * UUID.compareTo}'s signed order, so the expected keeper is worked out the database's way.
   */
  @Test
  void
      GivenTwoRequestsPendingForOneAssignmentRaisedTogether_WhenMigrated_ThenTheFirstQueuedWaits() {
    UUID shift = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    SwapRequestId one = requestOn(current, shift, requester, "REQUESTED", RAISED);
    SwapRequestId other = requestOn(current, shift, requester, "REQUESTED", RAISED);
    SwapRequestId firstQueued = unsignedly(one.value(), other.value()) < 0 ? one : other;
    SwapRequestId secondQueued = firstQueued.equals(one) ? other : one;

    migrateToTheLatest();

    assertThat(statusOf(firstQueued)).isEqualTo(SwapRequestStatus.REQUESTED);
    assertThat(statusOf(secondQueued)).isEqualTo(SwapRequestStatus.LAPSED);
  }

  /** Compares two identities as PostgreSQL compares a {@code uuid}: as unsigned bytes. */
  private static int unsignedly(UUID left, UUID right) {
    int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
    return high != 0
        ? high
        : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
  }

  /** A decided request never held the assignment, so it neither lapses nor makes another lapse. */
  @Test
  void GivenADecidedAndAPendingRequestForOneAssignment_WhenMigrated_ThenBothAreKept() {
    UUID shift = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    SwapRequestId rejected = requestOn(current, shift, requester, "REJECTED", RAISED);
    SwapRequestId pending = requestOn(current, shift, requester, "REQUESTED", LATER);

    migrateToTheLatest();

    assertThat(statusOf(rejected)).isEqualTo(SwapRequestStatus.REJECTED);
    assertThat(statusOf(pending)).isEqualTo(SwapRequestStatus.REQUESTED);
  }

  private SwapRequestStatus statusOf(SwapRequestId request) {
    return new JdbcSwapRequestRepository(jdbc)
        .findById(ACME, request)
        .map(SwapRequest::status)
        .orElseThrow();
  }

  private void migrateToTheLatest() {
    Flyway.configure().dataSource(dataSource).load().migrate();
  }

  /** Stores a published roster over the whole week, as V6 records one. */
  private void roster(RosterId id, OffsetDateTime publishedAt, OffsetDateTime supersededAt) {
    jdbc.sql(
            """
            INSERT INTO roster (id, organisation_id, starts_at, ends_at, published_at,
                                superseded_at, validated_against_constraint_version)
            VALUES (:id, :organisation, :startsAt, :endsAt, :publishedAt, :supersededAt, 1)
            """)
        .param("id", id.value())
        .param("organisation", ACME.value())
        .param("startsAt", MONDAY)
        .param("endsAt", NEXT_MONDAY)
        .param("publishedAt", publishedAt)
        .param("supersededAt", supersededAt)
        .update();
  }

  private SwapRequestId requestOn(RosterId roster, String status) {
    return requestOn(roster, UUID.randomUUID(), UUID.randomUUID(), status, RAISED);
  }

  private SwapRequestId requestOn(
      RosterId roster, UUID shift, UUID requester, String status, OffsetDateTime raisedAt) {
    // The work it asks to hand over, as the roster held it. Several requests may name one
    // assignment, so it is stored once.
    jdbc.sql(
            """
            INSERT INTO roster_assignment (roster_id, shift_id, employee_id)
            VALUES (:roster, :shift, :requester)
            ON CONFLICT DO NOTHING
            """)
        .param("roster", roster.value())
        .param("shift", shift)
        .param("requester", requester)
        .update();
    SwapRequestId id = SwapRequestId.newId();
    jdbc.sql(
            """
            INSERT INTO swap_request (id, organisation_id, roster_id, shift_id, requester_id,
                                      counterparty_id, status, requested_at)
            VALUES (:id, :organisation, :roster, :shift, :requester, :counterparty, :status,
                    :requestedAt)
            """)
        .param("id", id.value())
        .param("organisation", ACME.value())
        .param("roster", roster.value())
        .param("shift", shift)
        .param("requester", requester)
        .param("counterparty", UUID.randomUUID())
        .param("status", status)
        .param("requestedAt", raisedAt)
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
