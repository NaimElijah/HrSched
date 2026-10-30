package io.github.naimelijah.hrsched.notifications.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.naimelijah.hrsched.notifications.domain.model.NotAddressableException;
import io.github.naimelijah.hrsched.notifications.domain.model.NotificationId;
import io.github.naimelijah.hrsched.notifications.domain.model.UnknownNotificationException;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.NotATenantMemberException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Specification of which notification failure earns which status, what is counted, and what is
 * logged.
 *
 * <p>Driven directly rather than over HTTP, as {@code WorkforceExceptionHandlerTest} is and for the
 * same reason: the controller takes the organisation from the acting account ({@code actingIn},
 * ADR-031), so {@code requireAccessTo} compares an organisation with itself and a {@link
 * CrossTenantAccessException} cannot reach this handler from any HTTP test.
 *
 * <p>The log lines are asserted against Logback rather than the rendered JSON: the fields are this
 * class's, the encoding is Spring Boot's. Every value in them must be an identifier, because who
 * asked for whose inbox is personal data.
 */
class NotificationExceptionHandlerTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final NotificationExceptionHandler handler = new NotificationExceptionHandler(meters);

  private ch.qos.logback.classic.Logger logger;
  private ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void captureTheLog() {
    logger =
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(NotificationExceptionHandler.class.getName());
    captured = new ListAppender<>();
    captured.start();
    logger.addAppender(captured);
  }

  @AfterEach
  void releaseTheLog() {
    logger.detachAppender(captured);
  }

  private double denied(String kind) {
    var counter = meters.find("hrsched.access.denied").tag("kind", kind).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private ILoggingEvent onlyEvent() {
    assertThat(captured.list).hasSize(1);
    return captured.list.getFirst();
  }

  private static Map<String, Object> fieldsOf(ILoggingEvent event) {
    return event.getKeyValuePairs().stream()
        .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
  }

  // --- somebody without a personnel record -------------------------------------------------

  @Test
  void GivenAnActorWithNoPersonnelRecord_WhenTranslated_ThenItIsForbiddenSayingWhy() {
    ProblemDetail problem =
        handler.handleNotAddressable(new NotAddressableException(UserId.newId()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(problem.getTitle()).isEqualTo("Not permitted");
    assertThat(problem.getDetail())
        .isEqualTo("You have no personnel record, so you have no notifications.");
  }

  /** The exception's own message names the actor; the detail a caller is sent must not. */
  @Test
  void GivenAnActorWithNoPersonnelRecord_WhenTranslated_ThenTheDetailNamesNobody() {
    UserId actor = UserId.newId();

    ProblemDetail problem = handler.handleNotAddressable(new NotAddressableException(actor));

    assertThat(problem.getDetail()).doesNotContain(actor.value().toString());
  }

  @Test
  void GivenAnActorWithNoPersonnelRecord_WhenTranslated_ThenItIsCountedAsItsOwnKind() {
    handler.handleNotAddressable(new NotAddressableException(UserId.newId()));

    assertThat(denied("no_employee_record")).isEqualTo(1.0);
    assertThat(denied("cross_tenant")).isZero();
    assertThat(denied("operator_confined")).isZero();
  }

  @Test
  void GivenAnActorWithNoPersonnelRecord_WhenTranslated_ThenOneWarningNamesOnlyTheActor() {
    UserId actor = UserId.newId();

    handler.handleNotAddressable(new NotAddressableException(actor));

    ILoggingEvent event = onlyEvent();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .describedAs("the message is a constant; everything variable belongs in a field")
        .isEqualTo("Refused: an actor without a personnel record asked for their notifications");
    assertThat(fieldsOf(event)).containsOnly(Map.entry("actor", actor.value()));
  }

  // --- a notification that is not the caller's ----------------------------------------------

  /**
   * Somebody else's notification and one that never existed must read the same, so the message,
   * which names the notification, is replaced.
   */
  @Test
  void GivenAnUnknownNotification_WhenTranslated_ThenItIsNotFoundAndTheMessageIsReplaced() {
    NotificationId notification = NotificationId.newId();

    ProblemDetail problem = handler.handleUnknown(new UnknownNotificationException(notification));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(problem.getTitle()).isEqualTo("Not found");
    assertThat(problem.getDetail()).isEqualTo("No such resource.");
  }

  /** An ordinary miss is not a security event, and leaves no line and no count behind. */
  @Test
  void GivenAnUnknownNotification_WhenTranslated_ThenNothingIsCountedOrLogged() {
    handler.handleUnknown(new UnknownNotificationException(NotificationId.newId()));

    assertThat(meters.getMeters())
        .describedAs("no meter of any name or kind, so a typo never inflates hrsched.access.denied")
        .isEmpty();
    assertThat(captured.list).isEmpty();
  }

  /**
   * The same 404 an ordinary miss earns, on purpose, so nothing derived from the status can tell a
   * tenant breach from a typo; the message, which names the organisation reached for, is replaced.
   */
  @Test
  void GivenACrossTenantAttempt_WhenTranslated_ThenItIsNotFoundAndTheMessageIsReplaced() {
    OrganisationId theirs = OrganisationId.newId();

    ProblemDetail problem =
        handler.handleUnknown(new CrossTenantAccessException(UserId.newId(), theirs));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    assertThat(problem.getTitle()).isEqualTo("Not found");
    assertThat(problem.getDetail()).isEqualTo("No such resource.");
    assertThat(problem.getDetail()).doesNotContain(theirs.value().toString());
  }

  /** The counter that makes a security incident detectable, though the caller cannot tell. */
  @Test
  void GivenACrossTenantAttempt_WhenTranslated_ThenItIsCountedAsOne() {
    handler.handleUnknown(new CrossTenantAccessException(UserId.newId(), OrganisationId.newId()));

    assertThat(denied("cross_tenant")).isEqualTo(1.0);
    assertThat(denied("no_employee_record")).isZero();
  }

  @Test
  void GivenACrossTenantAttempt_WhenTranslated_ThenOneWarningNamesOnlyTheActorAndTheOrganisation() {
    UserId actor = UserId.newId();
    OrganisationId theirs = OrganisationId.newId();

    handler.handleUnknown(new CrossTenantAccessException(actor, theirs));

    ILoggingEvent event = onlyEvent();
    assertThat(event.getLevel()).isEqualTo(Level.WARN);
    assertThat(event.getFormattedMessage())
        .isEqualTo("Refused: an actor reached for another organisation's data");
    assertThat(fieldsOf(event))
        .containsOnly(Map.entry("actor", actor.value()), Map.entry("organisation", theirs.value()));
  }

  // --- the platform operator ---------------------------------------------------------------

  /**
   * A 403 rather than the 404 a cross-tenant attempt gets: that 404 exists so a tenant cannot learn
   * another organisation exists, and an operator already knows (ADR-030).
   */
  @Test
  void GivenTheOperatorActingInsideAnOrganisation_WhenTranslated_ThenItIsForbiddenAndCounted() {
    ProblemDetail problem =
        handler.handleOperatorConfined(new NotATenantMemberException(UserId.newId()));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    assertThat(problem.getTitle()).isEqualTo("Not permitted");
    assertThat(problem.getDetail())
        .isEqualTo("The platform operator may not act inside an organisation.");
    assertThat(denied("operator_confined")).isEqualTo(1.0);
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

  // --- what is caught at all ----------------------------------------------------------------

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
   * would quietly turn every unhandled bug into a tidy 404 or 403.
   */
  @Test
  void GivenTheHandler_WhenListingWhatItTranslates_ThenThereIsNoCatchAll() {
    assertThat(registeredFor(NotificationExceptionHandler.class))
        .isNotEmpty()
        .doesNotContain(Exception.class, RuntimeException.class, Throwable.class);
  }
}
