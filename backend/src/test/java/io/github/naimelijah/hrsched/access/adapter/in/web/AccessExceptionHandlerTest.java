package io.github.naimelijah.hrsched.access.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.naimelijah.hrsched.access.domain.model.InvalidOrganisationNameException;
import io.github.naimelijah.hrsched.access.domain.model.InvalidSignInNameException;
import io.github.naimelijah.hrsched.access.domain.model.NotThePlatformOperatorException;
import io.github.naimelijah.hrsched.access.domain.model.PasswordPolicy;
import io.github.naimelijah.hrsched.access.domain.model.UnknownAccountException;
import io.github.naimelijah.hrsched.access.domain.model.UnknownOrganisationException;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.NotATenantMemberException;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * Specification of the access refusals no HTTP test can reach today, of the type of those whose
 * type no HTTP test pins, and of what is recorded of one that can be reached.
 *
 * <p>A use case behind these routes refuses a role it does not permit, as {@code AccountService}
 * does for anybody but an Admin. Over HTTP the URL rule refuses that caller first, so the use
 * case's refusal is never seen there; loosening the rule would let it through, and without a
 * mapping it would be a 500. It is therefore driven here, through the method Spring would pick for
 * it from this advice, rather than by calling a handler method by name: a refusal with no handler
 * fails on that lookup, which is exactly the 500 this guards against.
 *
 * <p>The refusal is logged at WARN, asserted against Logback rather than the rendered JSON, and
 * counted under the metric every context's handler shares. So is an account named that the Admin's
 * organisation does not hold, whose line must say who asked; its 404 is pinned over HTTP in {@code
 * AccountHttpTest}.
 *
 * <p>The rest are reached over HTTP, but nothing there reads their {@code type}: a malformed name
 * or body, the operator's own and the tenant's refusals, and an organisation that is not there.
 * Each is driven here the same way, so a handler that stopped naming its kind fails here.
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
    assertThat(problem.getType()).hasToString("urn:hrsched:problem:not-permitted");
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

  /** Every failure the handler promises to render as a 400 carrying its own message. */
  private static Stream<RuntimeException> invalidInput() {
    return Stream.of(
        new InvalidOrganisationNameException(),
        new InvalidSignInNameException(),
        new MalformedRequestException("signInName is required"));
  }

  @ParameterizedTest
  @MethodSource("invalidInput")
  void GivenAnInvalidInput_WhenTranslated_ThenItIsABadRequestCarryingTheMessage(
      RuntimeException failure) throws Exception {
    ProblemDetail problem = translated(failure);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getTitle()).isEqualTo("Invalid request");
    assertThat(problem.getType()).hasToString("urn:hrsched:problem:invalid-request");
    assertThat(problem.getDetail()).isEqualTo(failure.getMessage());
  }

  @Test
  void GivenAnUnreadableBody_WhenTranslated_ThenItIsAMalformedRequestWithAGenericDetail()
      throws Exception {
    ProblemDetail problem =
        translated(new HttpMessageNotReadableException("raw parser noise", anUnreadableBody()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getTitle()).isEqualTo("Malformed request");
    assertThat(problem.getType()).hasToString("urn:hrsched:problem:malformed-request");
    assertThat(problem.getDetail()).doesNotContain("raw parser noise");
  }

  @Test
  void GivenATenantAdministeringThePlatform_WhenTranslated_ThenItIsForbidden() throws Exception {
    ProblemDetail problem = translated(new NotThePlatformOperatorException(UserId.newId()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(problem.getTitle()).isEqualTo("Not permitted");
    assertThat(problem.getType()).hasToString("urn:hrsched:problem:not-permitted");
    assertThat(problem.getDetail()).isEqualTo("Only the platform operator may do this.");
  }

  @Test
  void GivenTheOperatorActingInsideAnOrganisation_WhenTranslated_ThenItIsForbidden()
      throws Exception {
    ProblemDetail problem = translated(new NotATenantMemberException(UserId.newId()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(problem.getTitle()).isEqualTo("Not permitted");
    assertThat(problem.getType()).hasToString("urn:hrsched:problem:not-permitted");
    assertThat(problem.getDetail())
        .isEqualTo("The platform operator may not act inside an organisation.");
  }

  @Test
  void GivenAnOrganisationThatIsNotThere_WhenTranslated_ThenItIsNotFound() throws Exception {
    ProblemDetail problem = translated(new UnknownOrganisationException(OrganisationId.newId()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(problem.getType()).hasToString("urn:hrsched:problem:not-found");
    assertThat(problem.getDetail()).isEqualTo("No such resource.");
  }

  /** The same 404 an organisation that is not there earns, so the two cannot be told apart. */
  @Test
  void GivenACrossTenantAttempt_WhenTranslated_ThenItIsNotFound() throws Exception {
    ProblemDetail problem =
        translated(new CrossTenantAccessException(UserId.newId(), OrganisationId.newId()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(problem.getType()).hasToString("urn:hrsched:problem:not-found");
    assertThat(problem.getDetail()).isEqualTo("No such resource.");
  }

  /**
   * Spring 7 dropped the message-only constructors, so an unreadable body needs an input message.
   * Hand-written rather than mocked, like every other double here.
   */
  private static HttpInputMessage anUnreadableBody() {
    return new HttpInputMessage() {
      @Override
      public InputStream getBody() {
        return InputStream.nullInputStream();
      }

      @Override
      public HttpHeaders getHeaders() {
        return new HttpHeaders();
      }
    };
  }

  /**
   * An Admin naming an account their organisation does not hold. A hundred of these are somebody
   * looking for accounts that are not theirs, and reading one changes nothing, so the line has to
   * say who asked as well as what was named.
   */
  @Test
  void GivenAnUnknownAccount_WhenTranslated_ThenOneWarningNamesTheActorAndTheAccount() {
    UserId actor = UserId.newId();
    UserId named = UserId.newId();

    handler.handleUnknownAccount(
        new UnknownAccountException(named),
        new TenantActor(actor, OrganisationId.newId(), Role.ADMIN));

    assertThat(captured.list).hasSize(1);
    ILoggingEvent event = captured.list.getFirst();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .isEqualTo("Refused: an account this organisation does not hold was named");
    assertThat(event.getKeyValuePairs())
        .extracting(pair -> pair.key, pair -> pair.value)
        .containsExactlyInAnyOrder(tuple("actor", actor.value()), tuple("account", named.value()));
  }

  /** A principal that is not an actor: the refusal is still answered and recorded, without one. */
  @Test
  void GivenNoActor_WhenAnUnknownAccountIsTranslated_ThenItIsStillNotFoundAndRecorded() {
    UserId named = UserId.newId();

    ProblemDetail problem = handler.handleUnknownAccount(new UnknownAccountException(named), null);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(captured.list.getFirst().getKeyValuePairs())
        .extracting(pair -> pair.key, pair -> pair.value)
        .containsExactlyInAnyOrder(tuple("actor", null), tuple("account", named.value()));
    assertThat(
            meters.find("hrsched.access.denied").tag("kind", "unknown_account").counter().count())
        .isEqualTo(1.0);
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
   * Answers the refusal as Spring would: through the handler method this advice declares for it. A
   * method that also takes the acting principal is given none, as when nobody is signed in.
   */
  private ProblemDetail translated(RuntimeException refusal)
      throws IllegalAccessException, InvocationTargetException {
    Method method =
        new ExceptionHandlerMethodResolver(AccessExceptionHandler.class).resolveMethod(refusal);
    assertThat(method)
        .describedAs("a handler for %s, without which it is a 500", refusal.getClass().getName())
        .isNotNull();
    Object[] arguments = new Object[method.getParameterCount()];
    arguments[0] = refusal;
    return (ProblemDetail) method.invoke(handler, arguments);
  }
}
