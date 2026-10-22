package io.github.naimelijah.hrsched.configuration.persistence;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
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
 * <p>A method that only reads is marked read-only. That is not decoration: {@code
 * ActorAuthenticationConverter} calls {@code findActor} on <em>every</em> request whose token
 * verifies, so without it each such request would open a read-write transaction before the handler
 * opened its own. The naming convention is the only signal available, because the application layer
 * may carry no annotation to say so — and being wrong about it costs a wasted flush, never
 * correctness.
 *
 * <p>The proxy is a subclass rather than an interface proxy, so a bean keeps its concrete type and
 * {@code UseCaseConfiguration} reads as it did before. Nothing here works on a self-invocation — a
 * service calling its own method does not pass through the proxy — which changes nothing today,
 * because a nested call is already inside its caller's transaction.
 *
 * <p>A method named as outside any transaction is left alone entirely, for a use case that marks
 * out its own units of work because part of it must hold no connection: generation, whose solve
 * runs for seconds (ADR-045). It gets no transaction attribute at all rather than {@code NEVER} or
 * {@code SUPPORTS}, because those still open an empty scope with synchronisation active, and the
 * first repository call inside it would bind a connection to the thread until the method returned.
 */
public final class TransactionalUseCases {

  private TransactionalUseCases() {}

  /**
   * @param useCase a service implementing one or more driving ports
   * @param transactions the manager the transaction is begun on
   * @param outsideAnyTransaction methods of {@code useCase}, usually named through the driving port
   *     declaring them, that mark out their own units of work and must be left alone (ADR-045)
   * @return the same service, with every other public method wrapped in a transaction
   */
  @SuppressWarnings("unchecked")
  public static <T> T transactional(
      T useCase, PlatformTransactionManager transactions, Method... outsideAnyTransaction) {
    ProxyFactory factory = new ProxyFactory(useCase);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new TransactionInterceptor(transactions, byMethodName(List.of(outsideAnyTransaction))));
    return (T) factory.getProxy();
  }

  private static TransactionAttributeSource byMethodName(List<Method> outsideAnyTransaction) {
    return new TransactionAttributeSource() {
      @Override
      public TransactionAttribute getTransactionAttribute(Method method, Class<?> targetClass) {
        if (outsideAnyTransaction.stream().anyMatch(left -> isTheSame(left, method))) {
          return null;
        }
        DefaultTransactionAttribute attribute =
            new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_REQUIRED);
        attribute.setReadOnly(reads(method.getName()));
        return attribute;
      }
    };
  }

  /**
   * The proxy is asked about the implementing class's method, and the method left alone is usually
   * named through the interface declaring it, so they are compared by signature rather than
   * identity.
   */
  private static boolean isTheSame(Method named, Method invoked) {
    return named.getName().equals(invoked.getName())
        && Arrays.equals(named.getParameterTypes(), invoked.getParameterTypes());
  }

  /**
   * A method named {@code find…} reads; any other is treated as a write. A query named otherwise
   * still gets a read-write transaction, so the name is the only thing that makes a read cheap.
   */
  private static boolean reads(String method) {
    return method.startsWith("find");
  }
}
