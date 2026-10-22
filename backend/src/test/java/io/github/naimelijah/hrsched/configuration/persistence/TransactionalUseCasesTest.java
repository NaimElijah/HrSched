package io.github.naimelijah.hrsched.configuration.persistence;

import static io.github.naimelijah.hrsched.configuration.persistence.TransactionalUseCases.transactional;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The proxy every use case is wrapped in (ADR-023), and the one method it leaves alone (ADR-045),
 * against a real transaction manager over the suite's PostgreSQL.
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
}
