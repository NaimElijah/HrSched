package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Each unit of work generation marks out is one real transaction (ADR-045), against the suite's
 * PostgreSQL.
 */
class TransactionalUnitsOfWorkTest {

  private final TransactionalUnitsOfWork units =
      new TransactionalUnitsOfWork(PostgresDatabase.transactions());

  @BeforeEach
  void setUp() {
    PostgresDatabase.empty();
  }

  @Test
  void GivenAReadingUnit_WhenItRuns_ThenItIsOneTransactionThatOnlyReads() {
    boolean[] seen =
        units.reading(
            () ->
                new boolean[] {
                  TransactionSynchronizationManager.isActualTransactionActive(),
                  TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                });

    assertThat(seen).containsExactly(true, true);
  }

  @Test
  void GivenAWritingUnit_WhenItRuns_ThenItIsOneTransactionThatMayWrite() {
    boolean[] seen =
        units.writing(
            () ->
                new boolean[] {
                  TransactionSynchronizationManager.isActualTransactionActive(),
                  TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                });

    assertThat(seen).containsExactly(true, false);
  }

  @Test
  void GivenAWritingUnitThatThrows_WhenItRuns_ThenNothingItWroteIsStored() {
    OrganisationId acme = OrganisationId.newId();

    assertThatThrownBy(
            () ->
                units.writing(
                    () -> {
                      PostgresDatabase.given(acme);
                      throw new IllegalStateException("refused after writing");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(
            PostgresDatabase.jdbcClient()
                .sql("SELECT count(*) FROM organisation WHERE id = :id")
                .param("id", acme.value())
                .query(Long.class)
                .single())
        .isZero();
  }
}
