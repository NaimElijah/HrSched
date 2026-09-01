package io.github.naimelijah.hrsched.access.adapter.in.web;

import io.github.naimelijah.hrsched.access.domain.model.InvalidOrganisationNameException;
import io.github.naimelijah.hrsched.access.domain.model.NotThePlatformOperatorException;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates access failures into HTTP problem responses.
 *
 * <p>There is deliberately no catch-all, so an unexpected failure stays a 500 and stays visible.
 *
 * <p>As in the other two handlers, a cross-tenant attempt returns 404 rather than 403, because a
 * 403 would confirm that the organisation exists. Being refused as "not the operator" is a
 * different thing and is a 403: it discloses nothing, since the caller already knows whether they
 * claimed to be the operator.
 *
 * <p>It is also where these refusals are recorded, because it is the only place that sees every one
 * of them. The caller is told exactly what it was told before; only the operator gains anything.
 * See ADR-018.
 */
@RestControllerAdvice(assignableTypes = OrganisationController.class)
public class AccessExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(AccessExceptionHandler.class);

  /** Shared with the other handlers, so one query answers "who was refused" across all of them. */
  private static final String DENIED = "hrsched.access.denied";

  private final MeterRegistry meters;

  /**
   * @param meters where refusals are counted
   */
  public AccessExceptionHandler(MeterRegistry meters) {
    this.meters = meters;
  }

  private void denied(String kind) {
    Counter.builder(DENIED)
        .description("Refused attempts, by what refused them")
        .tag("kind", kind)
        .register(meters)
        .increment();
  }

  @ExceptionHandler({InvalidOrganisationNameException.class, MalformedRequestException.class})
  public ProblemDetail handleInvalidInput(RuntimeException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problem.setTitle("Invalid request");
    return problem;
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "The request body is malformed or is missing required fields.");
    problem.setTitle("Malformed request");
    return problem;
  }

  /** Administering the platform is not something a tenant can acquire by holding a role. */
  @ExceptionHandler(NotThePlatformOperatorException.class)
  public ProblemDetail handleNotTheOperator(NotThePlatformOperatorException exception) {
    log.atWarn()
        .addKeyValue("actor", exception.actor().value())
        .log("Refused: a tenant actor attempted to administer the platform");
    denied("operator");
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN, "Only the platform operator may do this.");
    problem.setTitle("Not permitted");
    return problem;
  }

  @ExceptionHandler(CrossTenantAccessException.class)
  public ProblemDetail handleCrossTenant(CrossTenantAccessException exception) {
    log.atWarn()
        .addKeyValue("actor", exception.actor().value())
        .addKeyValue("organisation", exception.organisation().value())
        .log("Refused: an actor reached for another organisation's data");
    denied("cross_tenant");
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setTitle("Not found");
    problem.setDetail("No such resource.");
    return problem;
  }
}
