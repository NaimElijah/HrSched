package io.github.naimelijah.hrsched.configuration.persistence;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Puts a transaction around every call to a use case.
 *
 * <p>This exists here rather than as an annotation on the services because the architecture test
 * forbids {@code org.springframework} inside {@code ..application..}, and that rule is not
 * incidental: ADR-003 keeps use cases constructible with {@code new} in a unit test, which an
 * annotation that only means something inside a container would end. So the boundary is applied at
 * the composition root, where Spring is allowed. See ADR-023.
 *
 * <p>A use case is exactly one unit of work, so every method gets {@code REQUIRED} propagation and
 * the default rollback rule — an unchecked exception rolls back, and every refusal in this codebase
 * is unchecked. Nested use cases join their caller's transaction, which is what makes approving a
 * swap atomic rather than four writes that might not all happen.
 *
 * <p>The proxy is a subclass rather than an interface proxy, so a bean keeps its concrete type and
 * {@code UseCaseConfiguration} reads as it did before. Nothing here works on a self-invocation — a
 * service calling its own method does not pass through the proxy — which changes nothing today,
 * because a nested call is already inside its caller's transaction.
 */
public final class TransactionalUseCases {

  private TransactionalUseCases() {}

  /**
   * @param useCase a service implementing one or more driving ports
   * @param transactions the manager the transaction is begun on
   * @return the same service, with every public method wrapped in a transaction
   */
  @SuppressWarnings("unchecked")
  public static <T> T transactional(T useCase, PlatformTransactionManager transactions) {
    ProxyFactory factory = new ProxyFactory(useCase);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new TransactionInterceptor(transactions, new MatchAlwaysTransactionAttributeSource()));
    return (T) factory.getProxy();
  }
}
