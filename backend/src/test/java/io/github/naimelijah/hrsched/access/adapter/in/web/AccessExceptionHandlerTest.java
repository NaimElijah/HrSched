package io.github.naimelijah.hrsched.access.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.naimelijah.hrsched.access.domain.model.PasswordPolicy;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * Specification of the access refusals no HTTP test can reach today.
 *
 * <p>A use case behind these routes refuses a role it does not permit, as {@code AccountService}
 * does for anybody but an Admin. Over HTTP the URL rule refuses that caller first, so the use
 * case's refusal is never seen there; loosening the rule would let it through, and without a
 * mapping it would be a 500. It is therefore driven here, through the method Spring would pick for
 * it from this advice, rather than by calling a handler method by name: a refusal with no handler
 * fails on that lookup, which is exactly the 500 this guards against.
 *
 * <p>The refusal is logged at WARN, asserted against Logback rather than the rendered JSON, and
 * counted under the metric every context's handler shares.
 */
class AccessExceptionHandlerTest {

  /** Invented: a policy to build the handler with; no refusal here is judged against it. */
  private static final PasswordPolicy INVENTED_POLICY = new PasswordPolicy(12, 72);

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final AccessExceptionHandler handler =
      new AccessExceptionHandler(meters, INVENTED_POLICY);

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureTheLog() {
    logger =
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(AccessExceptionHandler.class.getName());
    captured = new ListAppender<>();
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void releaseTheLog() {
    logger.detachAppender(captured);
  }

  @Test
  void GivenAUseCaseRefusingTheActorsRole_WhenTranslated_ThenItIsForbiddenWithoutNamingTheRole()
      throws Exception {
    ProblemDetail problem = translated(aRoleRefusal(UserId.newId()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(problem.getTitle()).isEqualTo("Not permitted");
    assertThat(problem.getDetail()).isEqualTo("Your role does not permit this.");
  }

  @Test
  void GivenAUseCaseRefusingTheActorsRole_WhenTranslated_ThenOneWarningNamesOnlyTheActorAndRole()
      throws Exception {
    UserId actor = UserId.newId();

    translated(aRoleRefusal(actor));

    assertThat(captured.list).hasSize(1);
    ILoggingEvent event = captured.list.getFirst();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .describedAs("the message is a constant; everything variable belongs in a field")
        .isEqualTo("Refused: the actor's role does not permit this");
    assertThat(
            event.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value)))
        .containsOnly(Map.entry("actor", actor.value()), Map.entry("role", Role.SCHEDULER));
  }

  @Test
  void GivenAUseCaseRefusingTheActorsRole_WhenTranslated_ThenItIsCountedAsARoleRefusal()
      throws Exception {
    translated(aRoleRefusal(UserId.newId()));

    var counter = meters.find("hrsched.access.denied").tag("kind", "role").counter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
  }

  /**
   * The refusal as an account use case raises it: a Scheduler asking for what only an Admin may do,
   * in their own organisation.
   */
  private static NotPermittedException aRoleRefusal(UserId actor) {
    OrganisationId organisation = OrganisationId.newId();
    TenantActor scheduler = new TenantActor(actor, organisation, Role.SCHEDULER);
    return catchThrowableOfType(
        NotPermittedException.class,
        () -> scheduler.requireAccessTo(organisation).requireRoleIn(Set.of(Role.ADMIN)));
  }

  /**
   * Answers the refusal as Spring would: through the handler method this advice declares for it.
   */
  private ProblemDetail translated(RuntimeException refusal)
      throws IllegalAccessException, InvocationTargetException {
    Method method =
        new ExceptionHandlerMethodResolver(AccessExceptionHandler.class).resolveMethod(refusal);
    assertThat(method)
        .describedAs("a handler for %s, without which it is a 500", refusal.getClass().getName())
        .isNotNull();
    return (ProblemDetail) method.invoke(handler, refusal);
  }
}
