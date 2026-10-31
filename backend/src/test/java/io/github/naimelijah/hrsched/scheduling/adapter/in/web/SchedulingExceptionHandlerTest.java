package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintViolation;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintsRevisedConcurrentlyException;
import io.github.naimelijah.hrsched.scheduling.domain.model.DuplicateAssignmentException;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationsStoppedException;
import io.github.naimelijah.hrsched.scheduling.domain.model.InvalidConstraintException;
import io.github.naimelijah.hrsched.scheduling.domain.model.InvalidHeadcountException;
import io.github.naimelijah.hrsched.scheduling.domain.model.InvalidObjectiveWeightException;
import io.github.naimelijah.hrsched.scheduling.domain.model.NoEmployeeRecordException;
import io.github.naimelijah.hrsched.scheduling.domain.model.Objective;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotVerifiableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterPublicationConflictException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapWithThemselfException;
import io.github.naimelijah.hrsched.scheduling.domain.model.TooManyGenerationsException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRequiredQualificationException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRosterException;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.InvalidTimeRangeException;
import io.github.naimelijah.hrsched.shared.domain.model.NotATenantMemberException;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
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
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * Specification of which scheduling failure earns which status.
 *
 * <p>The 422 carrying structured violations is the most product-critical mapping in the codebase.
 * Refusing to publish an illegal roster is this system's central claim, so the response explaining
 * the refusal has to say which person broke which rule in fields rather than in prose -- a caller
 * that has to parse a sentence back apart will eventually parse it wrong.
 *
 * <p>Driven directly rather than over HTTP, because the cross-tenant branch is unreachable from any
 * HTTP test: the organisation acted in is the acting account's own, taken from its token (ADR-031),
 * so a tenant actor can never fail its own access check, and reaching for another organisation's
 * roster over HTTP is answered by the unknown-roster branch instead.
 */
class SchedulingExceptionHandlerTest {

  /** Invented: how many generations were running when one more was refused. */
  private static final int BUSY_RUNS = 2;

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final SchedulingExceptionHandler handler = new SchedulingExceptionHandler(meters);

  private Logger logger;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureTheLog() {
    logger =
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(SchedulingExceptionHandler.class.getName());
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
    return meters.find("hrsched.access.denied").tag("kind", kind).counter() == null
        ? 0.0
        : meters.find("hrsched.access.denied").tag("kind", kind).counter().count();
  }

  private double refused(String reason) {
    return meters.find("hrsched.rosters.refused").tag("reason", reason).counter() == null
        ? 0.0
        : meters.find("hrsched.rosters.refused").tag("reason", reason).counter().count();
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
        new InvalidTimeRangeException(),
        new InvalidHeadcountException(0),
        new InvalidConstraintException("Minimum rest must be positive"),
        new InvalidObjectiveWeightException(Objective.FAIRNESS),
        new UnknownRequiredQualificationException(Set.of(QualificationId.newId())),
        new SwapWithThemselfException(EmployeeId.newId()),
        new MalformedRequestException("shift is required"));
  }

  @Test
  void GivenARosterBreachingTheRules_WhenTranslated_ThenItIsUnprocessable() {
    RosterNotCompliantException breach =
        new RosterNotCompliantException(
            List.of(
                new ConstraintViolation(
                    EmployeeId.newId(),
                    "maximum daily working time",
                    "worked PT12H on 2026-09-07")));

    ProblemDetail problem = handler.handleNotCompliant(breach);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    assertThat(problem.getTitle()).isEqualTo("Roster breaks the rules");
  }

  @Test
  void GivenARosterBreachingTheRules_WhenTranslated_ThenEveryViolationTravelsAsFields() {
    EmployeeId ada = EmployeeId.newId();
    RosterNotCompliantException breach =
        new RosterNotCompliantException(
            List.of(
                new ConstraintViolation(ada, "maximum daily working time", "worked PT12H"),
                new ConstraintViolation(ada, "minimum rest between assignments", "only PT4H")));

    ProblemDetail problem = handler.handleNotCompliant(breach);

    assertThat(problem.getProperties()).containsKey("violations");
    assertThat(problem.getProperties().get("violations"))
        .asInstanceOf(InstanceOfAssertFactories.list(ViolationResponse.class))
        .containsExactly(
            new ViolationResponse(ada.value(), "maximum daily working time", "worked PT12H"),
            new ViolationResponse(ada.value(), "minimum rest between assignments", "only PT4H"));
  }

  @Test
  void GivenARosterThatCannotBeChecked_WhenTranslated_ThenItIsUnprocessableRatherThanPublished() {
    ProblemDetail problem =
        handler.handleNotVerifiable(
            new RosterNotVerifiableException(RosterId.newId(), ShiftId.newId()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    assertThat(problem.getTitle()).isEqualTo("Roster cannot be checked against the rules");
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

  /**
   * Called directly, the method renders whatever it is handed; Spring routes it the types its
   * annotation names, and also an exception no handler names whose cause is one of them, as it
   * resolves by the cause next. So each row is also one of the named types, or it could pass above
   * and still reach a client as a 500.
   */
  @ParameterizedTest
  @MethodSource("invalidInput")
  void GivenAnInvalidInput_WhenSpringRoutesIt_ThenItReachesTheBadRequestHandler(
      RuntimeException failure) throws NoSuchMethodException {
    ExceptionHandler routing =
        SchedulingExceptionHandler.class
            .getMethod("handleInvalidInput", RuntimeException.class)
            .getAnnotation(ExceptionHandler.class);

    assertThat(routing.value()).contains(failure.getClass());
  }

  @Test
  void
      GivenARosterWhoseStatusForbidsTheChange_WhenTranslated_ThenItIsAConflictCarryingTheMessage() {
    RosterNotEditableException moved =
        new RosterNotEditableException(
            RosterId.newId(), RosterStatus.PUBLISHED, "a roster can only be changed while a draft");

    ProblemDetail problem = handler.handleStatusForbids(moved);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getTitle()).isEqualTo("Roster status forbids this");
    assertThat(problem.getDetail()).isEqualTo(moved.getMessage());
  }

  @Test
  void GivenAnUnreadableBody_WhenTranslated_ThenItIsABadRequestWithAGenericDetail() {
    ProblemDetail problem =
        handler.handleUnreadableBody(
            new HttpMessageNotReadableException("raw parser noise", anUnreadableBody()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    assertThat(problem.getTitle()).isEqualTo("Malformed request");
    assertThat(problem.getDetail()).doesNotContain("raw parser noise");
  }

  @Test
  void GivenARoleRefusal_WhenTranslated_ThenItIsForbiddenWithoutNamingTheRole() {
    ProblemDetail problem =
        handler.handleNotPermitted(
            new NotPermittedException(UserId.newId(), Role.EMPLOYEE, Set.of(Role.SCHEDULER)));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(problem.getTitle()).isEqualTo("Not permitted");
    assertThat(problem.getDetail()).isEqualTo("Your role does not permit this.");
  }

  /**
   * Both an unknown roster and a cross-tenant reach answer the same way on purpose. A caller must
   * not be able to tell "there is no such roster" from "there is, but not yours".
   */
  @Test
  void GivenAnUnknownRoster_WhenTranslated_ThenItIsNotFoundAndSaysNothingMore() {
    ProblemDetail problem = handler.handleUnknown(new UnknownRosterException(RosterId.newId()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(problem.getTitle()).isEqualTo("Not found");
    assertThat(problem.getDetail()).isEqualTo("No such resource.");
  }

  @Test
  void GivenACrossTenantAttempt_WhenTranslated_ThenItIsNotFoundAndTheMessageIsReplaced() {
    OrganisationId theirs = OrganisationId.newId();

    ProblemDetail problem =
        handler.handleUnknown(new CrossTenantAccessException(UserId.newId(), theirs));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(problem.getDetail()).isEqualTo("No such resource.");
    assertThat(problem.getDetail()).doesNotContain(theirs.value().toString());
  }

  /**
   * The counter that makes a security incident detectable. A cross-tenant attempt is answered with
   * the same 404 as an ordinary miss, on purpose, so {@code http.server.requests} cannot tell the
   * two apart — and before this it left no trace anywhere at all.
   */
  @Test
  void GivenACrossTenantAttempt_WhenTranslated_ThenItIsCountedAsOne() {
    handler.handleUnknown(new CrossTenantAccessException(UserId.newId(), OrganisationId.newId()));

    assertThat(denied("cross_tenant")).isEqualTo(1.0);
    assertThat(denied("role")).isZero();
  }

  @Test
  void GivenAnOrdinaryUnknownRoster_WhenTranslated_ThenNothingIsCountedAsASecurityEvent() {
    handler.handleUnknown(new UnknownRosterException(RosterId.newId()));

    assertThat(denied("cross_tenant")).isZero();
  }

  @Test
  void GivenARoleRefusal_WhenTranslated_ThenItIsCountedSeparatelyFromATenantBreach() {
    handler.handleNotPermitted(
        new NotPermittedException(UserId.newId(), Role.ADMIN, Set.of(Role.SCHEDULER)));

    assertThat(denied("role")).isEqualTo(1.0);
    assertThat(denied("cross_tenant")).isZero();
  }

  @Test
  void GivenARosterBreachingTheRules_WhenTranslated_ThenTheRefusalIsCountedByItsReason() {
    handler.handleNotCompliant(
        new RosterNotCompliantException(
            List.of(new ConstraintViolation(EmployeeId.newId(), "a rule", "some detail"))));

    assertThat(refused("not_compliant")).isEqualTo(1.0);
    assertThat(refused("not_verifiable")).isZero();
  }

  @Test
  void GivenAPublicationOfARosterThatCannotBeChecked_WhenTranslated_ThenItIsCountedApart() {
    handler.handleNotVerifiable(
        new RosterNotVerifiableException(RosterId.newId(), ShiftId.newId()).refusingPublication());

    assertThat(refused("not_verifiable")).isEqualTo(1.0);
    assertThat(refused("not_compliant")).isZero();
  }

  /**
   * #134: assigning, assessing a draft and reading one's own schedule refuse the same way when a
   * shift cannot be resolved, and answer the same, but nothing was published, so nothing is counted
   * as a refused publication.
   */
  @Test
  void
      GivenARosterThatCannotBeCheckedOutsidePublication_WhenTranslated_ThenNoPublicationIsRefused() {
    ProblemDetail problem =
        handler.handleNotVerifiable(
            new RosterNotVerifiableException(RosterId.newId(), ShiftId.newId()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.value());
    assertThat(refused("not_verifiable")).isZero();
  }

  /**
   * A publication that lost a race is a conflict, not a bad request: nothing it sent was wrong, and
   * reloading and trying again is the remedy (ADR-026).
   *
   * <p>Two things stay out of the response. The store's own words, and the roster being published:
   * when a swap approval loses, that roster is a replacement the caller never saw, rolled back with
   * the refusal, so naming it would point them at something that does not exist.
   */
  @Test
  void GivenAPublicationThatLostARace_WhenTranslated_ThenItIsAConflictSayingWhatToDo() {
    RosterId replacement = RosterId.newId();
    RosterPublicationConflictException conflict =
        new RosterPublicationConflictException(
            replacement, new IllegalStateException("what the store said"));

    ProblemDetail problem = handler.handleConcurrentPublication(conflict);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getTitle()).isEqualTo("Published concurrently");
    assertThat(problem.getDetail())
        .isEqualTo(conflict.getMessage())
        .doesNotContain("what the store said")
        .doesNotContain(replacement.value().toString());
  }

  /**
   * A second press of the same assignment is a conflict with what the roster holds, not a bad
   * request, so the board reloads. Found as Spring finds it, so a refusal still routed to the 400
   * handler fails here.
   */
  @Test
  void GivenAnAssignmentTheRosterAlreadyHolds_WhenSpringRoutesIt_ThenItIsAConflict()
      throws Exception {
    DuplicateAssignmentException duplicate = new DuplicateAssignmentException();
    Method routed =
        new ExceptionHandlerMethodResolver(SchedulingExceptionHandler.class)
            .resolveMethodByThrowable(duplicate);
    assertThat(routed).describedAs("the handler Spring routes the duplicate to").isNotNull();

    ProblemDetail problem = (ProblemDetail) routed.invoke(handler, duplicate);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getTitle()).isEqualTo("Already assigned");
  }

  /**
   * #238: a revision of the rules that lost a race is a conflict too, rather than the 500 the
   * store's own exception became. The store's words stay out of the response, and nothing is
   * logged: losing a race is ordinary. The handler is found as Spring finds it, from the exception
   * and its cause, so a refusal routed elsewhere fails here.
   */
  @Test
  void GivenARevisionOfTheRulesThatLostARace_WhenTranslated_ThenItIsAConflictSayingWhatToDo()
      throws Exception {
    ConstraintsRevisedConcurrentlyException conflict =
        new ConstraintsRevisedConcurrentlyException(
            2, new IllegalStateException("what the store said"));
    Method routed =
        new ExceptionHandlerMethodResolver(SchedulingExceptionHandler.class)
            .resolveMethodByThrowable(conflict);
    assertThat(routed).describedAs("the handler Spring routes the conflict to").isNotNull();

    ProblemDetail problem = (ProblemDetail) routed.invoke(handler, conflict);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(problem.getTitle()).isEqualTo("Revised concurrently");
    assertThat(problem.getDetail())
        .isEqualTo(conflict.getMessage())
        .doesNotContain("what the store said");
    assertThat(captured.list).isEmpty();
  }

  @Test
  void GivenAPublicationThatLostARace_WhenTranslated_ThenTheRefusalIsCountedByItsReason() {
    handler.handleConcurrentPublication(
        new RosterPublicationConflictException(
            RosterId.newId(), new IllegalStateException("what the store said")));

    assertThat(refused("concurrent_publication")).isEqualTo(1.0);
    assertThat(refused("not_compliant")).isZero();
  }

  // --- A refusal worth somebody's attention is logged at WARN, by identity only (ADR-018) -------
  //
  // The counters say how many; the log line says who, which is what an operator investigating one
  // needs. Every field is an identity or a role, never a name: a log is copied to places a
  // database is not.

  @Test
  void GivenACrossTenantAttempt_WhenTranslated_ThenItIsLoggedAtWarnNamingTheActorAndTheTenant() {
    UserId actor = UserId.newId();
    OrganisationId theirs = OrganisationId.newId();

    handler.handleUnknown(new CrossTenantAccessException(actor, theirs));

    ILoggingEvent logged = onlyEvent();
    assertThat(logged.getLevel()).isEqualTo(Level.WARN);
    assertThat(logged.getFormattedMessage())
        .isEqualTo("Refused: an actor reached for another organisation's data");
    assertThat(fieldsOf(logged))
        .containsOnly(Map.entry("actor", actor.value()), Map.entry("organisation", theirs.value()));
  }

  @Test
  void GivenARoleRefusal_WhenTranslated_ThenItIsLoggedAtWarnNamingTheActorAndTheRoleHeld() {
    UserId actor = UserId.newId();

    handler.handleNotPermitted(
        new NotPermittedException(actor, Role.ADMIN, Set.of(Role.SCHEDULER)));

    ILoggingEvent logged = onlyEvent();
    assertThat(logged.getLevel()).isEqualTo(Level.WARN);
    assertThat(logged.getFormattedMessage())
        .isEqualTo("Refused: the actor's role does not permit this");
    assertThat(fieldsOf(logged))
        .containsOnly(Map.entry("actor", actor.value()), Map.entry("role", Role.ADMIN));
  }

  @Test
  void GivenTheOperatorActingInsideAnOrganisation_WhenTranslated_ThenItIsLoggedAtWarnNamingThem() {
    UserId operator = UserId.newId();

    handler.handleOperatorConfined(new NotATenantMemberException(operator));

    ILoggingEvent logged = onlyEvent();
    assertThat(logged.getLevel()).isEqualTo(Level.WARN);
    assertThat(logged.getFormattedMessage())
        .isEqualTo("Refused: the platform operator reached inside an organisation");
    assertThat(fieldsOf(logged)).containsOnly(Map.entry("actor", operator.value()));
  }

  /**
   * The roster and the shift stay out of the line: what to look at is in the 422 the caller got.
   */
  @Test
  void
      GivenAPublicationOfARosterThatCannotBeChecked_WhenTranslated_ThenItIsLoggedAtWarnBareOfFields() {
    handler.handleNotVerifiable(
        new RosterNotVerifiableException(RosterId.newId(), ShiftId.newId()).refusingPublication());

    ILoggingEvent logged = onlyEvent();
    assertThat(logged.getLevel()).isEqualTo(Level.WARN);
    assertThat(logged.getFormattedMessage())
        .isEqualTo("Publication refused: the roster names work that cannot be checked");
    assertThat(logged.getKeyValuePairs()).isNullOrEmpty();
  }

  /** As a refused publication is, but not called one (#134). */
  @Test
  void
      GivenARosterThatCannotBeCheckedOutsidePublication_WhenTranslated_ThenItIsLoggedBareOfFields() {
    handler.handleNotVerifiable(
        new RosterNotVerifiableException(RosterId.newId(), ShiftId.newId()));

    ILoggingEvent logged = onlyEvent();
    assertThat(logged.getLevel()).isEqualTo(Level.WARN);
    assertThat(logged.getFormattedMessage())
        .isEqualTo("Refused: the roster names work that cannot be checked");
    assertThat(logged.getKeyValuePairs()).isNullOrEmpty();
  }

  @Test
  void GivenAPublicationThatLostARace_WhenTranslated_ThenItIsLoggedAtWarnNamingOnlyTheRoster() {
    RosterId replacement = RosterId.newId();

    handler.handleConcurrentPublication(
        new RosterPublicationConflictException(
            replacement, new IllegalStateException("what the store said")));

    ILoggingEvent logged = onlyEvent();
    assertThat(logged.getLevel()).isEqualTo(Level.WARN);
    assertThat(logged.getFormattedMessage())
        .isEqualTo(
            "Publication refused by the schema: an overlapping roster was published concurrently");
    assertThat(fieldsOf(logged)).containsOnly(Map.entry("roster", replacement.value()));
  }

  @Test
  void GivenAnActorWithoutAPersonnelRecord_WhenTranslated_ThenItIsLoggedAtWarnNamingOnlyThem() {
    UserId actor = UserId.newId();

    handler.handleNoEmployeeRecord(new NoEmployeeRecordException(actor));

    ILoggingEvent logged = onlyEvent();
    assertThat(logged.getLevel()).isEqualTo(Level.WARN);
    assertThat(logged.getFormattedMessage())
        .isEqualTo("Refused: an actor without a personnel record asked for their own schedule");
    assertThat(fieldsOf(logged)).containsOnly(Map.entry("actor", actor.value()));
  }

  /** Saturation, not anybody's doing: the line names nobody, and the counter says how often. */
  @Test
  void GivenEveryGenerationRunBusy_WhenTranslated_ThenItIsLoggedAtWarnCarryingNoFields() {
    handler.handleTooManyGenerations(new TooManyGenerationsException(BUSY_RUNS));

    ILoggingEvent logged = onlyEvent();
    assertThat(logged.getLevel()).isEqualTo(Level.WARN);
    assertThat(logged.getFormattedMessage()).isEqualTo("Generation refused: every run is busy");
    assertThat(logged.getKeyValuePairs()).isNullOrEmpty();
  }

  /**
   * A runner that has stopped is not saturation: the caller is told 503 and to ask again, the line
   * says nothing about runs being busy, and the busy counter is not moved. The handler is found as
   * Spring finds it, from the exception, so a refusal routed to nothing fails here rather than
   * reaching the caller as a 500.
   */
  @Test
  void GivenAStoppedRunnersRefusal_WhenSpringRoutesIt_ThenItIsUnavailableAndNotCountedAsBusy()
      throws Exception {
    GenerationsStoppedException stopped =
        new GenerationsStoppedException(new IllegalStateException("what the pool said"));
    Method routed =
        new ExceptionHandlerMethodResolver(SchedulingExceptionHandler.class)
            .resolveMethodByThrowable(stopped);
    assertThat(routed).describedAs("the handler Spring routes the refusal to").isNotNull();

    ProblemDetail problem = (ProblemDetail) routed.invoke(handler, stopped);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    assertThat(problem.getDetail())
        .isEqualTo(stopped.getMessage())
        .contains("ask again")
        .doesNotContain("what the pool said");
    ILoggingEvent logged = onlyEvent();
    assertThat(logged.getLevel()).isEqualTo(Level.WARN);
    assertThat(logged.getFormattedMessage()).doesNotContain("busy");
    assertThat(logged.getKeyValuePairs()).isNullOrEmpty();
    assertThat(meters.find("hrsched.generations.busy").counter()).isNull();
  }

  /** A miss is not an incident: logging every 404 would bury the ones that are. */
  @Test
  void GivenAnOrdinaryUnknownRoster_WhenTranslated_ThenNothingIsLogged() {
    handler.handleUnknown(new UnknownRosterException(RosterId.newId()));

    assertThat(captured.list).isEmpty();
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
    assertThat(registeredFor(SchedulingExceptionHandler.class))
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
}
