package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.InvalidTimeRangeException;
import io.github.naimelijah.hrsched.shared.domain.model.NotATenantMemberException;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
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
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;

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
 */
class WorkforceExceptionHandlerTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final WorkforceExceptionHandler handler = new WorkforceExceptionHandler(meters);

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
            new HttpMessageNotReadableException("raw parser noise", anUnreadableBody()));

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
}
