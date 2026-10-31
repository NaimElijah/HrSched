package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.InvalidTimeRangeException;
import io.github.naimelijah.hrsched.shared.domain.model.NotATenantMemberException;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.workforce.domain.model.InvalidContractTermsException;
import io.github.naimelijah.hrsched.workforce.domain.model.InvalidEmployeeNameException;
import io.github.naimelijah.hrsched.workforce.domain.model.InvalidQualificationNameException;
import io.github.naimelijah.hrsched.workforce.domain.model.OverlappingUnavailabilityException;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownDeclarationException;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownEmployeeException;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownQualificationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.util.Arrays;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Specification of which workforce failure earns which status.
 *
 * <p>Driven directly rather than over HTTP, for a reason worth recording: every workforce
 * controller takes the organisation it acts in from the acting account itself ({@code actingIn},
 * ADR-031), so {@code requireAccessTo} compares an organisation with itself and can never fail for
 * a caller arriving over HTTP. Isolation there is produced by the repositories' organisation filter
 * returning nothing, which becomes an unknown-record 404. {@link CrossTenantAccessException} still
 * guards every direct caller and would guard a second adapter, but the branch that translates it is
 * unreachable from any HTTP test -- so it is reachable only from here.
 *
 * <p>The three refusals are also logged, at WARN, and those lines are asserted against Logback
 * rather than the rendered JSON: the fields are this class's, the encoding is Spring Boot's. This
 * is the context holding names, absence and contracted hours, so every value logged must be an
 * identifier or a role, and every message a constant.
 */
class WorkforceExceptionHandlerTest {

  /** The refusal's constant message, so the line found is this refusal's. */
  private static final String UNKNOWN_MEMBER_REFUSED =
      "Refused: a request body carried a member its request type does not have";

  /** Jackson as the application configures it for request bodies: refusing an unknown member. */
  private static final JsonMapper STRICT =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /** Invented: a member no request to add an employee has. */
  private static final String UNKNOWN_MEMBER = "preferredNickname";

  /** Invented: what was sent in it. */
  private static final String UNKNOWN_VALUE = "an invented nickname";

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final WorkforceExceptionHandler handler = new WorkforceExceptionHandler(meters);

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureTheLog() {
    logger =
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(WorkforceExceptionHandler.class.getName());
    captured = new ListAppender<>();
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void releaseTheLog() {
    logger.detachAppender(captured);
  }

  private ILoggingEvent onlyEvent() {
    assertThat(captured.list).hasSize(1);
    return captured.list.getFirst();
  }

  private static Map<String, Object> fieldsOf(ILoggingEvent event) {
    return event.getKeyValuePairs().stream()
        .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
  }

  private double denied(String kind) {
    var counter = meters.find("hrsched.access.denied").tag("kind", kind).counter();
    return counter == null ? 0.0 : counter.count();
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

  /** Every failure the handler promises to render as a 400 carrying its own message. */
  private static Stream<RuntimeException> invalidInput() {
    return Stream.of(
        new InvalidEmployeeNameException(),
        new InvalidQualificationNameException(),
        new InvalidContractTermsException(),
        new InvalidTimeRangeException(),
        new OverlappingUnavailabilityException(),
        new MalformedRequestException("name is required"));
  }

  /** Every failure the handler promises to render as a 404 carrying its own message. */
  private static Stream<RuntimeException> unknownRecords() {
    return Stream.of(
        new UnknownEmployeeException(EmployeeId.newId()),
        new UnknownQualificationException(QualificationId.newId()),
        new UnknownDeclarationException());
  }

  @ParameterizedTest
  @MethodSource("invalidInput")
  void GivenAnInvalidInput_WhenTranslated_ThenItIsABadRequestCarryingTheMessage(
      RuntimeException failure) {
    ProblemDetail problem = handler.handleInvalidInput(failure);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getTitle()).isEqualTo("Invalid request");
    assertThat(problem.getDetail()).isEqualTo(failure.getMessage());
  }

  @ParameterizedTest
  @MethodSource("unknownRecords")
  void GivenAnUnknownRecord_WhenTranslated_ThenItIsNotFoundCarryingTheMessage(
      RuntimeException failure) {
    ProblemDetail problem = handler.handleUnknown(failure);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(problem.getTitle()).isEqualTo("Not found");
    assertThat(problem.getDetail()).isEqualTo(failure.getMessage());
  }

  @Test
  void GivenAnUnreadableBody_WhenTranslated_ThenItIsABadRequestWithAGenericDetail() {
    ProblemDetail problem =
        handler.handleUnreadableBody(
            new HttpMessageNotReadableException("raw parser noise", anUnreadableBody()),
            anActor(UserId.newId()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getTitle()).isEqualTo("Malformed request");
    assertThat(problem.getDetail()).doesNotContain("raw parser noise");
  }

  @Test
  void GivenARoleRefusal_WhenTranslated_ThenItIsForbiddenWithoutNamingTheRole() {
    NotPermittedException refusal =
        new NotPermittedException(UserId.newId(), Role.EMPLOYEE, Set.of(Role.ADMIN));

    ProblemDetail problem = handler.handleNotPermitted(refusal);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(problem.getTitle()).isEqualTo("Not permitted");
    assertThat(problem.getDetail()).isEqualTo("Your role does not permit this.");
  }

  /**
   * The message names the organisation that was reached for. Rendering it would confirm the
   * resource exists somewhere, which is the whole reason this is a 404 and not a 403.
   */
  @Test
  void GivenACrossTenantAttempt_WhenTranslated_ThenItIsNotFoundAndTheMessageIsReplaced() {
    OrganisationId theirs = OrganisationId.newId();
    CrossTenantAccessException refusal = new CrossTenantAccessException(UserId.newId(), theirs);

    ProblemDetail problem = handler.handleCrossTenant(refusal);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(problem.getTitle()).isEqualTo("Not found");
    assertThat(problem.getDetail()).isEqualTo("No such resource.");
    assertThat(problem.getDetail()).doesNotContain(theirs.value().toString());
  }

  /**
   * The counter that makes a security incident detectable. The response is the same 404 an ordinary
   * miss earns, on purpose, so nothing derived from the status code can tell the two apart.
   */
  @Test
  void GivenACrossTenantAttempt_WhenTranslated_ThenItIsCountedAsOne() {
    handler.handleCrossTenant(
        new CrossTenantAccessException(UserId.newId(), OrganisationId.newId()));

    assertThat(denied("cross_tenant")).isEqualTo(1.0);
    assertThat(denied("role")).isZero();
  }

  @Test
  void GivenAnOrdinaryUnknownRecord_WhenTranslated_ThenNothingIsCountedAsASecurityEvent() {
    handler.handleUnknown(new UnknownEmployeeException(EmployeeId.newId()));

    assertThat(denied("cross_tenant")).isZero();
    assertThat(denied("role")).isZero();
  }

  @Test
  void GivenARoleRefusal_WhenTranslated_ThenItIsCountedSeparatelyFromATenantBreach() {
    handler.handleNotPermitted(
        new NotPermittedException(UserId.newId(), Role.EMPLOYEE, Set.of(Role.ADMIN)));

    assertThat(denied("role")).isEqualTo(1.0);
    assertThat(denied("cross_tenant")).isZero();
  }

  /**
   * Every exception type this handler is registered for. Spring reads the annotation's own list
   * when it has one and falls back to the method's parameter types when it does not, so both have
   * to be considered to know what is really caught.
   */
  private static Stream<Class<?>> registeredFor(Class<?> handler) {
    return Arrays.stream(handler.getDeclaredMethods())
        .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
        .flatMap(
            method -> {
              Class<? extends Throwable>[] declared =
                  method.getAnnotation(ExceptionHandler.class).value();
              return declared.length > 0
                  ? Arrays.stream(declared)
                  : Arrays.stream(method.getParameterTypes())
                      .filter(Throwable.class::isAssignableFrom);
            });
  }

  /**
   * The class javadoc promises an unexpected failure stays a 500 and stays visible. A catch-all
   * added later would quietly turn every unhandled bug into a tidy 400, which is the difference
   * between a bug that pages somebody and a bug that looks like the caller's fault.
   */
  @Test
  void GivenTheHandler_WhenListingWhatItTranslates_ThenThereIsNoCatchAll() {
    assertThat(registeredFor(WorkforceExceptionHandler.class))
        .isNotEmpty()
        .doesNotContain(Exception.class, RuntimeException.class, Throwable.class);
  }

  /**
   * The platform operator reaching inside an organisation: a 403 rather than the 404 a cross-tenant
   * attempt gets. That 404 exists so a tenant cannot learn another organisation exists; an operator
   * already knows, because listing them is the job (ADR-030).
   */
  @Test
  void GivenTheOperatorActingInsideAnOrganisation_WhenTranslated_ThenItIsForbiddenAndCounted() {
    UserId operator = UserId.newId();

    ProblemDetail problem = handler.handleOperatorConfined(new NotATenantMemberException(operator));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(problem.getTitle()).isEqualTo("Not permitted");
    assertThat(problem.getDetail())
        .isEqualTo("The platform operator may not act inside an organisation.");
    assertThat(denied("operator_confined")).isEqualTo(1.0);
  }

  // --- what each refusal leaves in the log (ADR-018) ----------------------------------------

  @Test
  void GivenARoleRefusal_WhenTranslated_ThenOneWarningNamesOnlyTheActorAndTheirRole() {
    UserId actor = UserId.newId();

    handler.handleNotPermitted(new NotPermittedException(actor, Role.EMPLOYEE, Set.of(Role.ADMIN)));

    ILoggingEvent event = onlyEvent();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .describedAs("the message is a constant; everything variable belongs in a field")
        .isEqualTo("Refused: the actor's role does not permit this");
    assertThat(fieldsOf(event))
        .containsOnly(Map.entry("actor", actor.value()), Map.entry("role", Role.EMPLOYEE));
  }

  @Test
  void GivenACrossTenantAttempt_WhenTranslated_ThenOneWarningNamesOnlyTheActorAndTheOrganisation() {
    UserId actor = UserId.newId();
    OrganisationId theirs = OrganisationId.newId();

    handler.handleCrossTenant(new CrossTenantAccessException(actor, theirs));

    ILoggingEvent event = onlyEvent();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .isEqualTo("Refused: an actor reached for another organisation's data");
    assertThat(fieldsOf(event))
        .containsOnly(Map.entry("actor", actor.value()), Map.entry("organisation", theirs.value()));
  }

  @Test
  void GivenTheOperatorActingInsideAnOrganisation_WhenTranslated_ThenOneWarningNamesOnlyThem() {
    UserId operator = UserId.newId();

    handler.handleOperatorConfined(new NotATenantMemberException(operator));

    ILoggingEvent event = onlyEvent();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .isEqualTo("Refused: the platform operator reached inside an organisation");
    assertThat(fieldsOf(event)).containsOnly(Map.entry("actor", operator.value()));
  }

  /** An ordinary miss is not a refusal, and leaves no line behind. */
  @Test
  void GivenAnOrdinaryUnknownRecord_WhenTranslated_ThenNothingIsLogged() {
    handler.handleUnknown(new UnknownEmployeeException(EmployeeId.newId()));

    assertThat(captured.list).isEmpty();
  }

  // --- a body naming a member its request type does not have (ADR-058) -------------------------

  /**
   * The body is bound before any use case runs, so a caller the use case would have refused for
   * their role, and recorded, is answered as malformed instead. The line names who asked and the
   * request type, never the member or anything sent, and carries no exception: Jackson's message
   * quotes the member.
   */
  @Test
  void
      GivenABodyWithAMemberItsTypeLacks_WhenTranslated_ThenOneWarningNamesOnlyTheActorAndTheRequestType() {
    UserId actor = UserId.newId();

    ProblemDetail problem =
        handler.handleUnreadableBody(aBodyWithAMemberItsTypeLacks(), anActor(actor));

    assertThat(problem.getTitle()).isEqualTo("Malformed request");
    ILoggingEvent event = onlyEvent();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .describedAs("the message is a constant; everything variable belongs in a field")
        .isEqualTo(UNKNOWN_MEMBER_REFUSED);
    assertThat(event.getKeyValuePairs())
        .extracting(pair -> pair.key, pair -> pair.value)
        .containsExactly(tuple("actor", actor.value()), tuple("requestType", "AddEmployeeRequest"));
    assertThat(event.getThrowableProxy()).isNull();
  }

  @Test
  void GivenABodyWithAMemberItsTypeLacks_WhenTranslated_ThenItIsCountedOnce() {
    handler.handleUnreadableBody(aBodyWithAMemberItsTypeLacks(), anActor(UserId.newId()));

    assertThat(denied("unknown_member")).isEqualTo(1.0);
  }

  /** A principal that is not an actor: the refusal is still answered and recorded, without one. */
  @Test
  void GivenNoActor_WhenABodyWithAMemberItsTypeLacksIsTranslated_ThenItIsRecordedWithoutOne() {
    handler.handleUnreadableBody(aBodyWithAMemberItsTypeLacks(), null);

    assertThat(onlyEvent().getKeyValuePairs())
        .extracting(pair -> pair.key, pair -> pair.value)
        .containsExactly(tuple("actor", null), tuple("requestType", "AddEmployeeRequest"));
    assertThat(denied("unknown_member")).isEqualTo(1.0);
  }

  /** Only the unknown member is a refusal worth a line; a body that is not JSON is a fault. */
  @Test
  void GivenABodyThatIsNotJson_WhenTranslated_ThenNothingIsLoggedOrCounted() {
    JacksonException broken =
        catchThrowableOfType(
            JacksonException.class, () -> STRICT.readValue("{\"name\":", AddEmployeeRequest.class));

    handler.handleUnreadableBody(
        new HttpMessageNotReadableException(broken.getMessage(), broken, anUnreadableBody()),
        anActor(UserId.newId()));

    assertThat(captured.list).isEmpty();
    assertThat(meters.find("hrsched.access.denied").counters()).isEmpty();
  }

  /**
   * What Spring hands the handler for such a body: Jackson's own refusal, from reading one, wrapped
   * as the converter wraps it, its message quoting the member.
   */
  private static HttpMessageNotReadableException aBodyWithAMemberItsTypeLacks() {
    UnrecognizedPropertyException unknown =
        catchThrowableOfType(
            UnrecognizedPropertyException.class,
            () ->
                STRICT.readValue(
                    "{\"name\":\"Ada Lovelace\",\"%s\":\"%s\"}"
                        .formatted(UNKNOWN_MEMBER, UNKNOWN_VALUE),
                    AddEmployeeRequest.class));
    return new HttpMessageNotReadableException(unknown.getMessage(), unknown, anUnreadableBody());
  }

  private static Actor anActor(UserId id) {
    return new TenantActor(id, OrganisationId.newId(), Role.EMPLOYEE);
  }
}
