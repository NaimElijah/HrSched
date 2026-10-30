package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.InvalidTimeRangeException;
import io.github.naimelijah.hrsched.shared.domain.model.NotATenantMemberException;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.workforce.domain.model.InvalidContractTermsException;
import io.github.naimelijah.hrsched.workforce.domain.model.InvalidEmployeeNameException;
import io.github.naimelijah.hrsched.workforce.domain.model.InvalidQualificationNameException;
import io.github.naimelijah.hrsched.workforce.domain.model.OverlappingUnavailabilityException;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownDeclarationException;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownEmployeeException;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownQualificationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.exc.StreamConstraintsException;

/**
 * Translates workforce failures into HTTP problem responses.
 *
 * <p>Only the domain knows a rule was broken; only the transport knows what status that deserves.
 * There is deliberately no catch-all, so an unexpected failure stays a 500 and stays visible.
 *
 * <p>Note what a cross-tenant attempt returns: 404, not 403. A 403 would confirm that the resource
 * exists in some other organisation, which is itself a small leak. The caller is told only that
 * there is nothing here for them.
 *
 * <p>It is also where refusals are recorded, because it is the only place that sees every one of
 * them. The caller is told exactly what it was told before; only the operator gains anything. See
 * ADR-018.
 */
@RestControllerAdvice(assignableTypes = {EmployeeController.class, QualificationController.class})
public class WorkforceExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(WorkforceExceptionHandler.class);

  /**
   * Shared by every class that counts a refusal, in every context and in the security chain, so one
   * query answers "who was refused" across all of them.
   */
  private static final String DENIED = "hrsched.access.denied";

  private final MeterRegistry meters;

  /**
   * @param meters where refusals are counted
   */
  public WorkforceExceptionHandler(MeterRegistry meters) {
    this.meters = meters;
  }

  private void denied(String kind) {
    Counter.builder(DENIED)
        .description("Refused attempts, by what refused them")
        .tag("kind", kind)
        .register(meters)
        .increment();
  }

  @ExceptionHandler({
    InvalidEmployeeNameException.class,
    InvalidQualificationNameException.class,
    InvalidContractTermsException.class,
    InvalidTimeRangeException.class,
    OverlappingUnavailabilityException.class,
    MalformedRequestException.class
  })
  public ProblemDetail handleInvalidInput(RuntimeException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problem.setTitle("Invalid request");
    return problem;
  }

  /**
   * A body Jackson stopped reading because it broke one of its stream constraints - the document
   * length {@code hrsched.web.max-json-document-bytes} sets, or a nesting or value-length limit -
   * is a 413 rather than a 400 (RFC 9110 section 15.5.14). Its JSON may be perfectly well formed,
   * and calling it malformed sends a client looking for a fault that is not there. Both answers are
   * fixed sentences, because Jackson's message quotes the input it failed on.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception) {
    if (exception.getMostSpecificCause() instanceof StreamConstraintsException) {
      ProblemDetail problem =
          ProblemDetail.forStatusAndDetail(
              HttpStatus.CONTENT_TOO_LARGE,
              "The request body exceeds a limit on its size, its nesting or the length of a value"
                  + " in it.");
      problem.setTitle("Request too large");
      return problem;
    }
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "The request body is malformed or is missing required fields.");
    problem.setTitle("Malformed request");
    return problem;
  }

  @ExceptionHandler(NotPermittedException.class)
  public ProblemDetail handleNotPermitted(NotPermittedException exception) {
    log.atWarn()
        .addKeyValue("actor", exception.actor().value())
        .addKeyValue("role", exception.held())
        .log("Refused: the actor's role does not permit this");
    denied("role");
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Your role does not permit this.");
    problem.setTitle("Not permitted");
    return problem;
  }

  @ExceptionHandler({
    UnknownEmployeeException.class,
    UnknownQualificationException.class,
    UnknownDeclarationException.class
  })
  public ProblemDetail handleUnknown(RuntimeException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    problem.setTitle("Not found");
    return problem;
  }

  /**
   * The refusal the requirements call a security incident rather than a bug. It used to become a
   * 404 and leave no trace at all, which made the incident undetectable; what the caller is told is
   * unchanged.
   */
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

  /**
   * The platform operator reaching inside an organisation. A 403 rather than the 404 a cross-tenant
   * attempt gets: that 404 exists so a tenant cannot learn another organisation exists, and an
   * operator already knows, because listing them is the job. See ADR-030.
   */
  @ExceptionHandler(NotATenantMemberException.class)
  public ProblemDetail handleOperatorConfined(NotATenantMemberException exception) {
    log.atWarn()
        .addKeyValue("actor", exception.actor().value())
        .log("Refused: the platform operator reached inside an organisation");
    denied("operator_confined");
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN, "The platform operator may not act inside an organisation.");
    problem.setTitle("Not permitted");
    return problem;
  }
}
