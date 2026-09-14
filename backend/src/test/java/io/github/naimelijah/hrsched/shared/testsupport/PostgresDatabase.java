package io.github.naimelijah.hrsched.shared.testsupport;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The one PostgreSQL every test in this suite shares.
 *
 * <p>Started once per JVM and never stopped: Testcontainers' resource reaper removes it when the
 * JVM exits, and starting a container per test class would cost more than the whole suite. The real
 * schema is applied by the real Flyway migrations, so a test that passes is evidence about the
 * migrations too — an ad-hoc {@code CREATE TABLE} here would let the schema and the code agree with
 * each other while both disagreed with production.
 *
 * <p><strong>Docker is required to build.</strong> There is deliberately no {@code
 * disabledWithoutDocker} and no in-memory fallback: a check that quietly does not run still reports
 * green, which is the failure mode {@code ci.yml} already refuses for path filters. See ADR-004.
 */
public final class PostgresDatabase {

  /**
   * Pinned rather than floating: a test suite whose database changes underneath it on a Tuesday is
   * a test suite nobody trusts.
   */
  private static final String IMAGE = "postgres:18";

  private static final PostgreSQLContainer CONTAINER = startContainer();
  private static final DataSource DATA_SOURCE = migratedDataSource();
  private static final JdbcClient JDBC_CLIENT = JdbcClient.create(DATA_SOURCE);
  private static final PlatformTransactionManager TRANSACTIONS =
      new DataSourceTransactionManager(DATA_SOURCE);

  private PostgresDatabase() {}

  private static PostgreSQLContainer startContainer() {
    PostgreSQLContainer container = new PostgreSQLContainer(IMAGE);
    container.start();
    return container;
  }

  private static DataSource migratedDataSource() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(CONTAINER.getJdbcUrl());
    config.setUsername(CONTAINER.getUsername());
    config.setPassword(CONTAINER.getPassword());
    DataSource dataSource = new HikariDataSource(config);
    Flyway.configure().dataSource(dataSource).load().migrate();
    return dataSource;
  }

  /**
   * @return the container, for a {@code @SpringBootTest} to point its own datasource at
   */
  public static PostgreSQLContainer container() {
    return CONTAINER;
  }

  /**
   * @return a client over the migrated schema, for an adapter test that wants no Spring context
   */
  public static JdbcClient jdbcClient() {
    return JDBC_CLIENT;
  }

  /**
   * Transactions over the same pool {@link #jdbcClient()} draws from, so a statement made through
   * that client inside one of them joins it.
   *
   * <p>For an adapter test that has to hold a transaction open while something else happens: two
   * writers racing, or a second connection finding what the first one holds. Everything else runs
   * in autocommit and needs none.
   *
   * @return a transaction manager over the migrated schema
   */
  public static PlatformTransactionManager transactions() {
    return TRANSACTIONS;
  }

  /**
   * Registers tenants, so rows may be stored against them.
   *
   * <p>Needed because {@code organisation_id} is a real foreign key on every table: a row belonging
   * to a tenant nobody onboarded is exactly the hazard ADR-020 exists to prevent, so the schema
   * refuses it and a test that wants one has to say so.
   */
  public static void given(OrganisationId... organisations) {
    for (OrganisationId organisation : organisations) {
      JDBC_CLIENT
          .sql("INSERT INTO organisation (id, name) VALUES (:id, :name)")
          .param("id", organisation.value())
          .param("name", "Test organisation " + organisation.value())
          .update();
    }
  }

  /**
   * Empties every table, so one test cannot see another's rows.
   *
   * <p>Truncates rather than re-migrating, which would cost seconds per test. The table list comes
   * from the catalogue rather than being written out, so a migration that adds a table cannot leave
   * a stale one behind for the next test to trip over. Flyway's own history table is excluded, for
   * the obvious reason.
   */
  public static void empty() {
    // A table name cannot be a bind parameter, so this one statement is built by concatenation.
    // What is concatenated comes from the catalogue through quote_ident and never from a caller,
    // and an empty schema would mean the migrations did not run, so it is louder to fail here.
    String tables =
        JDBC_CLIENT
            .sql(
                """
                SELECT string_agg(quote_ident(tablename), ', ')
                FROM pg_tables
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """)
            .query(String.class)
            .optional()
            .orElseThrow(
                () -> new IllegalStateException("The schema has no tables; did Flyway run?"));
    JDBC_CLIENT.sql("TRUNCATE TABLE " + tables + " CASCADE").update();
  }
}
