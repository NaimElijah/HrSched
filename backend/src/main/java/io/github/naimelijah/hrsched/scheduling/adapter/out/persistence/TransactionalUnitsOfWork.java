package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import io.github.naimelijah.hrsched.scheduling.application.port.out.UnitsOfWork;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Each unit of work is one database transaction, begun the way the use-case proxy begins one:
 * {@code REQUIRED}, so a unit opened inside a transaction joins it (ADR-023, ADR-045).
 */
@Component
public class TransactionalUnitsOfWork implements UnitsOfWork {

  private final TransactionTemplate reads;
  private final TransactionTemplate writes;

  /**
   * @param transactions the manager every use case's transaction is begun on
   */
  public TransactionalUnitsOfWork(PlatformTransactionManager transactions) {
    this.writes = new TransactionTemplate(transactions);
    this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    this.reads = new TransactionTemplate(transactions);
    this.reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    this.reads.setReadOnly(true);
  }

  @Override
  public <T> T reading(Supplier<T> work) {
    return reads.execute(status -> work.get());
  }

  @Override
  public <T> T writing(Supplier<T> work) {
    return writes.execute(status -> work.get());
  }
}
