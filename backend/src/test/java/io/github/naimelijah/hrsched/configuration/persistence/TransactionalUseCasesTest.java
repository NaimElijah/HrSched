package io.github.naimelijah.hrsched.configuration.persistence;

import static io.github.naimelijah.hrsched.configuration.persistence.TransactionalUseCases.transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;
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

    /** Shares the left-alone method's name but not its signature, so it is not left alone. */
    public Seen generate(Integer times) {
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

    /** Writes, then refuses, the way a use case refusing after its first write would. */
    public void changeThenRefuse(UUID organisation) {
      insertOrganisation(jdbc, organisation);
      throw new IllegalStateException("an invented refusal after the write");
    }

    /** The transaction PostgreSQL is running this call in. */
    public String transactionId() {
      return currentTransactionId(jdbc);
    }

    /** A writer misnamed as a reader: what #119 asks the convention to be held to. */
    public void findAndWrite(UUID organisation) {
      insertOrganisation(jdbc, organisation);
    }

    private String transactionReadOnly() {
      return jdbc.sql("SHOW transaction_read_only").query(String.class).single();
    }
  }

  /** A use case that calls another, as approving a swap calls the ones it is made of. */
  static class NestingProbe {

    private final JdbcClient jdbc;
    private final DatabaseProbe inner;

    NestingProbe(JdbcClient jdbc, DatabaseProbe inner) {
      this.jdbc = jdbc;
      this.inner = inner;
    }

    /** The transaction it runs in, then the one the use case it calls runs in. */
    public List<String> changeThroughAnother() {
      return List.of(currentTransactionId(jdbc), inner.transactionId());
    }
  }

  private static String currentTransactionId(JdbcClient jdbc) {
    return jdbc.sql("SELECT pg_current_xact_id()::text").query(String.class).single();
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

  @Test
  void GivenAMethodThatWritesThenThrows_WhenCalled_ThenItsWriteIsRolledBack() {
    UUID organisation = UUID.randomUUID();
    DatabaseProbe probe = databaseProbe();

    assertThatThrownBy(() -> probe.changeThenRefuse(organisation))
        .isInstanceOf(IllegalStateException.class);

    assertThat(organisationExists(organisation))
        .as("the write made before the refusal survived it")
        .isFalse();
  }

  @Test
  void GivenAUseCaseCallingAnother_WhenCalled_ThenBothRunInOneDatabaseTransaction() {
    NestingProbe outer =
        transactional(
            new NestingProbe(PostgresDatabase.jdbcClient(), databaseProbe()),
            PostgresDatabase.transactions());

    List<String> transactions = outer.changeThroughAnother();

    assertThat(transactions.get(1))
        .as("the called use case's transaction id, against its caller's")
        .isEqualTo(transactions.get(0));
  }

  @Test
  void GivenAnOverloadOfAMethodLeftAlone_WhenTheOverloadIsCalled_ThenItRunsInATransaction()
      throws NoSuchMethodException {
    Probe probe = transactional(new Probe(), PostgresDatabase.transactions(), generating());

    assertThat(probe.generate(1)).isEqualTo(new Seen(true, true, false));
  }
}
