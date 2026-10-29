package io.github.naimelijah.hrsched.configuration.persistence;

import static io.github.naimelijah.hrsched.configuration.persistence.TransactionalUseCases.transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The proxy every use case is wrapped in (ADR-023), and the one method it leaves alone (ADR-045),
 * against a real transaction manager over the suite's PostgreSQL.
 *
 * <p>Read-only is asked of the database as well as of Spring's thread-local, because the flag
 * Spring keeps is only a promise until the driver hands it on (#119). The organisation names
 * written here are invented, and each carries a random suffix so no run meets another's row.
 */
class TransactionalUseCasesTest {

  /** What a method could see of the transaction it was called in. */
  record Seen(boolean transaction, boolean synchronisation, boolean readOnly) {

    static Seen now() {
      return new Seen(
          TransactionSynchronizationManager.isActualTransactionActive(),
          TransactionSynchronizationManager.isSynchronizationActive(),
          TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    }
  }

  /** The port a method left alone is usually named through. */
  interface Generating {
    Seen generate(String what);
  }

  /** A use case whose every method reports the transaction it ran in. */
  static class Probe implements Generating {

    public Seen change() {
      return Seen.now();
    }

    public Seen findSomething() {
      return Seen.now();
    }

    @Override
    public Seen generate(String what) {
      return Seen.now();
    }
  }

  /** SQLSTATE {@code read_only_sql_transaction}: a write attempted in a read-only transaction. */
  private static final String READ_ONLY_SQL_TRANSACTION = "25006";

  /** A use case whose methods ask PostgreSQL about the transaction they ran in, or write in it. */
  static class DatabaseProbe {

    private final JdbcClient jdbc;

    DatabaseProbe(JdbcClient jdbc) {
      this.jdbc = jdbc;
    }

    /** Named as a write, the way every use case not named {@code find…} is. */
    public String change() {
      return transactionReadOnly();
    }

    public String findSomething() {
      return transactionReadOnly();
    }

    /** A writer misnamed as a reader: what #119 asks the convention to be held to. */
    public void findAndWrite(UUID organisation) {
      insertOrganisation(jdbc, organisation);
    }

    private String transactionReadOnly() {
      return jdbc.sql("SHOW transaction_read_only").query(String.class).single();
    }
  }

  private static void insertOrganisation(JdbcClient jdbc, UUID organisation) {
    jdbc.sql("INSERT INTO organisation (id, name) VALUES (:id, :name)")
        .param("id", organisation)
        .param("name", "Invented organisation " + organisation)
        .update();
  }

  private static boolean organisationExists(UUID organisation) {
    return PostgresDatabase.jdbcClient()
        .sql("SELECT EXISTS (SELECT 1 FROM organisation WHERE id = :id)")
        .param("id", organisation)
        .query(Boolean.class)
        .single();
  }

  private static DatabaseProbe databaseProbe() {
    return transactional(
        new DatabaseProbe(PostgresDatabase.jdbcClient()), PostgresDatabase.transactions());
  }

  private static Method generating() throws NoSuchMethodException {
    return Generating.class.getMethod("generate", String.class);
  }

  @Test
  void GivenAMethodLeftOutsideAnyTransaction_WhenCalled_ThenNoTransactionOrSynchronisationIsOpen()
      throws NoSuchMethodException {
    Probe probe = transactional(new Probe(), PostgresDatabase.transactions(), generating());

    assertThat(probe.generate("a draft")).isEqualTo(new Seen(false, false, false));
  }

  @Test
  void GivenAMethodLeftOutsideAnyTransaction_WhenAnotherIsCalled_ThenThatOneIsStillTransactional()
      throws NoSuchMethodException {
    Probe probe = transactional(new Probe(), PostgresDatabase.transactions(), generating());

    assertThat(probe.change()).isEqualTo(new Seen(true, true, false));
  }

  @Test
  void GivenNothingLeftAlone_WhenAnyMethodIsCalled_ThenItRunsInATransaction() {
    Probe probe = transactional(new Probe(), PostgresDatabase.transactions());

    assertThat(probe.generate("a draft")).isEqualTo(new Seen(true, true, false));
  }

  @Test
  void GivenAMethodNamedFind_WhenCalled_ThenItsTransactionOnlyReads() {
    Probe probe = transactional(new Probe(), PostgresDatabase.transactions());

    assertThat(probe.findSomething()).isEqualTo(new Seen(true, true, true));
  }

  @Test
  void GivenAMethodNamedFind_WhenItAsksTheDatabase_ThenTheDatabaseTransactionIsReadOnly() {
    assertThat(databaseProbe().findSomething()).isEqualTo("on");
  }

  @Test
  void GivenAMethodNotNamedFind_WhenItAsksTheDatabase_ThenTheDatabaseTransactionMayWrite() {
    assertThat(databaseProbe().change()).isEqualTo("off");
  }

  /**
   * A writer named {@code find…} is not a wasted flush: Spring hands the read-only flag to the
   * connection, pgjdbc begins the transaction {@code READ ONLY}, and PostgreSQL refuses the write.
   */
  @Test
  void GivenAMethodNamedFindThatWrites_WhenCalled_ThenTheDatabaseRefusesTheWrite() {
    UUID organisation = UUID.randomUUID();
    DatabaseProbe probe = databaseProbe();

    Throwable refusal = catchThrowable(() -> probe.findAndWrite(organisation));

    assertThat(refusal).isInstanceOf(DataAccessException.class);
    assertThat(NestedExceptionUtils.getMostSpecificCause(refusal))
        .isInstanceOfSatisfying(
            SQLException.class,
            cause ->
                assertThat(cause.getSQLState())
                    .as("SQLSTATE of %s", cause.getMessage())
                    .isEqualTo(READ_ONLY_SQL_TRANSACTION));
    assertThat(organisationExists(organisation)).as("the refused row was stored").isFalse();
  }
}
