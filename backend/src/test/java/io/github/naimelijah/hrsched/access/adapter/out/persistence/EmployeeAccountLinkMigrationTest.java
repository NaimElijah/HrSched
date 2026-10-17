package io.github.naimelijah.hrsched.access.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.naimelijah.hrsched.access.domain.model.Account;
import io.github.naimelijah.hrsched.access.domain.model.OperatorMembership;
import io.github.naimelijah.hrsched.access.domain.model.SignInName;
import io.github.naimelijah.hrsched.access.domain.model.TenantMembership;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What V9 does to accounts stored before an Employee account had to be linked (R34).
 *
 * <p>The suite's own database is always fully migrated, so it can never show what a migration does
 * to rows already there. This test makes a database of its own in the shared container, migrates it
 * only as far as V8, stores accounts and sessions the way V3 and V4 stored them, and then lets the
 * rest run.
 *
 * <p>An active Employee account linked to nobody is disabled, and every session it still held is
 * ended as a disabling ends one (R17). Nothing else changes. Afterwards the schema refuses such an
 * account outright.
 *
 * <p>Every sign-in name and password hash here is invented, and so is every instant.
 */
class EmployeeAccountLinkMigrationTest {

  /** The last version before an Employee account had to be linked. */
  private static final String BEFORE_EMPLOYEE_ACCOUNTS_WERE_LINKED = "8";

  /** PostgreSQL's {@code check_violation}: a {@code CHECK} constraint refused the row. */
  private static final String CHECK_VIOLATION = "23514";

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final String HASH = "{bcrypt}$2a$04$invented.test.value";
  private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-09-01T09:00:00Z");
  private static final OffsetDateTime SIGNED_IN = OffsetDateTime.parse("2026-09-02T09:00:00Z");
  private static final OffsetDateTime SESSION_ENDS = OffsetDateTime.parse("2026-09-02T21:00:00Z");
  private static final OffsetDateTime SIGNED_OUT = OffsetDateTime.parse("2026-09-02T10:00:00Z");

  private String database;
  private HikariDataSource dataSource;
  private JdbcClient jdbc;

  @BeforeEach
  void storeTheWayV8Did() {
    // A database name cannot be a bind parameter. This one is made here from a UUID, never from
    // anything a caller supplied, and CREATE DATABASE cannot run inside a transaction, which the
    // shared client's autocommit connection is not in.
    database = "employee_accounts_" + UUID.randomUUID().toString().replace("-", "");
    PostgresDatabase.jdbcClient().sql("CREATE DATABASE " + database).update();
    dataSource = dataSourceFor(database);
    jdbc = JdbcClient.create(dataSource);
    Flyway.configure()
        .dataSource(dataSource)
        .target(BEFORE_EMPLOYEE_ACCOUNTS_WERE_LINKED)
        .load()
        .migrate();

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
  void GivenAnActiveEmployeeAccountLinkedToNobody_WhenMigrated_ThenItIsDisabled() {
    UserId unlinked = tenantAccount("unlinked@example.com", Role.EMPLOYEE, null, true);

    migrateToTheLatest();

    Account migrated = accounts().find(ACME, unlinked).orElseThrow();
    assertThat(migrated.active()).isFalse();
    assertThat(migrated.membership())
        .describedAs("disabled, and still linked to nobody: whom it was for is not invented")
        .isEqualTo(new TenantMembership(ACME, Role.EMPLOYEE, Optional.empty()));
  }

  @Test
  void
      GivenTheLiveSessionOfAnEmployeeAccountLinkedToNobody_WhenMigrated_ThenItIsEndedAsADisabling() {
    UserId unlinked = tenantAccount("unlinked@example.com", Role.EMPLOYEE, null, true);
    UUID live = session(unlinked, null, null);

    Instant beforeMigrating = theDatabasesClock();
    migrateToTheLatest();
    Instant afterMigrating = theDatabasesClock();

    StoredSession ended = sessionRow(live);
    assertThat(ended.revocation()).contains("ACCOUNT_DISABLED");
    assertThat(ended.revokedAt().orElseThrow())
        .describedAs("the moment the migration ran, not one from before it or invented")
        .isBetween(beforeMigrating, afterMigrating);
  }

  /** A session somebody had already ended keeps the ending it had: that is its history. */
  @Test
  void GivenASessionAlreadyEnded_WhenItsAccountIsDisabledByTheMigration_ThenItKeepsItsEnding() {
    UserId unlinked = tenantAccount("unlinked@example.com", Role.EMPLOYEE, null, true);
    UUID signedOut = session(unlinked, SIGNED_OUT, "SIGNED_OUT");

    migrateToTheLatest();

    StoredSession kept = sessionRow(signedOut);
    assertThat(kept.revocation()).contains("SIGNED_OUT");
    assertThat(kept.revokedAt()).contains(SIGNED_OUT.toInstant());
  }

  @Test
  void GivenALinkedEmployeeAccount_WhenMigrated_ThenItAndItsSessionAreUntouched() {
    EmployeeId ada = EmployeeId.newId();
    UserId linked = tenantAccount("linked@example.com", Role.EMPLOYEE, ada, true);
    UUID live = session(linked, null, null);

    migrateToTheLatest();

    Account migrated = accounts().find(ACME, linked).orElseThrow();
    assertThat(migrated.active()).isTrue();
    assertThat(migrated.membership())
        .isEqualTo(new TenantMembership(ACME, Role.EMPLOYEE, Optional.of(ada)));
    assertThat(sessionRow(live).revokedAt()).isEmpty();
  }

  /** R34 is about the Employee role alone: a Scheduler or an Admin may be nobody's. */
  @Test
  void GivenASchedulerAndAnAdminLinkedToNobody_WhenMigrated_ThenBothStayActive() {
    UserId scheduler = tenantAccount("scheduler@example.com", Role.SCHEDULER, null, true);
    UserId admin = tenantAccount("admin@example.com", Role.ADMIN, null, true);
    UUID schedulersSession = session(scheduler, null, null);

    migrateToTheLatest();

    assertThat(accounts().find(ACME, scheduler).orElseThrow().active()).isTrue();
    assertThat(accounts().find(ACME, admin).orElseThrow().active()).isTrue();
    assertThat(sessionRow(schedulersSession).revokedAt()).isEmpty();
  }

  @Test
  void GivenTheOperator_WhenMigrated_ThenItStaysActive() {
    operatorAccount("operator@example.com");

    migrateToTheLatest();

    Account operator =
        accounts().findBySignInName(new SignInName("operator@example.com")).orElseThrow();
    assertThat(operator.membership()).isEqualTo(new OperatorMembership());
    assertThat(operator.active()).isTrue();
  }

  /** The schema now refuses, for a writer that skips the domain, what the domain refuses. */
  @Test
  void GivenTheMigratedSchema_WhenAnActiveEmployeeAccountLinkedToNobodyIsStored_ThenItIsRefused() {
    migrateToTheLatest();

    assertThatThrownBy(() -> tenantAccount("unlinked@example.com", Role.EMPLOYEE, null, true))
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class,
            refused -> assertThat(refused.getSQLState()).isEqualTo(CHECK_VIOLATION));
  }

  private JdbcAccountRepository accounts() {
    return new JdbcAccountRepository(jdbc);
  }

  private Instant theDatabasesClock() {
    return jdbc.sql("SELECT now()").query(OffsetDateTime.class).single().toInstant();
  }

  private void migrateToTheLatest() {
    Flyway.configure().dataSource(dataSource).load().migrate();
  }

  /** How a session ended, if it has: both are empty for one still live. */
  private record StoredSession(Optional<Instant> revokedAt, Optional<String> revocation) {}

  private StoredSession sessionRow(UUID session) {
    return jdbc.sql("SELECT revoked_at, revocation FROM sign_in_session WHERE id = :id")
        .param("id", session)
        .query(
            (row, number) ->
                new StoredSession(
                    Optional.ofNullable(row.getObject("revoked_at", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant),
                    Optional.ofNullable(row.getString("revocation"))))
        .single();
  }

  /** Stores a tenant account as V3 stored one, with no failures behind it and no lock. */
  private UserId tenantAccount(String signInName, Role role, EmployeeId employee, boolean active) {
    UserId id = UserId.newId();
    jdbc.sql(
            """
            INSERT INTO account (id, sign_in_name, password_hash, kind, organisation_id, role,
                                 employee_id, active, password_change_required, failed_sign_ins,
                                 locked_until, created_at)
            VALUES (:id, :signInName, :hash, 'TENANT', :organisation, :role, :employee, :active,
                    false, 0, NULL, :createdAt)
            """)
        .param("id", id.value())
        .param("signInName", signInName)
        .param("hash", HASH)
        .param("organisation", ACME.value())
        .param("role", role.name())
        .param("employee", employee == null ? null : employee.value())
        .param("active", active)
        .param("createdAt", CREATED)
        .update();
    return id;
  }

  /** Stores the platform operator as V3 stored it: no organisation, no role, nobody's employee. */
  private void operatorAccount(String signInName) {
    jdbc.sql(
            """
            INSERT INTO account (id, sign_in_name, password_hash, kind, organisation_id, role,
                                 employee_id, active, password_change_required, failed_sign_ins,
                                 locked_until, created_at)
            VALUES (:id, :signInName, :hash, 'OPERATOR', NULL, NULL, NULL, true, false, 0, NULL,
                    :createdAt)
            """)
        .param("id", UUID.randomUUID())
        .param("signInName", signInName)
        .param("hash", HASH)
        .param("createdAt", CREATED)
        .update();
  }

  /** Stores a session as V4 stored one: live when both of the last two are null. */
  private UUID session(UserId account, OffsetDateTime revokedAt, String revocation) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO sign_in_session (id, account_id, started_at, expires_at, revoked_at,
                                         revocation)
            VALUES (:id, :account, :startedAt, :expiresAt, :revokedAt, :revocation)
            """)
        .param("id", id)
        .param("account", account.value())
        .param("startedAt", SIGNED_IN)
        .param("expiresAt", SESSION_ENDS)
        .param("revokedAt", revokedAt)
        .param("revocation", revocation)
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
