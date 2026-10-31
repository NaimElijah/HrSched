package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

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
 * them. What the caller is told does not depend on the recording; only whoever reads the log sees
 * it. See ADR-018.
 */
@RestControllerAdvice(assignableTypes = {EmployeeController.class, QualificationController.class})
public class WorkforceExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(WorkforceExceptionHandler.class);

  /**
   * Shared by every class that counts a refusal, in every context and in the security chain, so one
   * query answers "who was refused" across all of them.
   */
  private static final String DENIED = "hrsched.access.denied";

  /**
   * What every body Jackson cannot bind is told, whatever was wrong with it: not JSON, a member
   * missing or unknown, or a value of the wrong form. One constant sentence true of each, so
   * nothing in it comes from what was sent, as Jackson's own message, which quotes it, would.
   */
  private static final String MALFORMED_BODY =
      "The request body is not valid JSON, or it lacks a member this request requires, has one it"
          + " does not take, or has one whose value is of the wrong form.";

  private final MeterRegistry meters;

  /**
   * @param meters where refusals are counted
   */
  public WorkforceExceptionHandler(MeterRegistry meters) {
    this.meters = meters;
  }

  /**
   * Counts a refusal. {@code kind} becomes a tag, so it stays one of a small fixed set of constants
   * and never anything the caller sent.
   */
  private void denied(String kind) {
    Counter.builder(DENIED)
        .description("Refused attempts, by what refused them")
        .tag("kind", kind)
        .register(meters)
        .increment();
  }

  /**
   * Input the domain or this adapter refuses is a 400 whose detail is the exception's own message.
   * Each of these keeps what was sent out of its message (ADR-024), so the detail repeats nothing
   * of it.
   */
  @ExceptionHandler({
    InvalidEmployeeNameException.class,
    InvalidQualificationNameException.class,
    InvalidContractTermsException.class,
    InvalidTimeRangeException.class,
    MalformedRequestException.class
  })
  public ProblemDetail handleInvalidInput(RuntimeException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problem.setTitle("Invalid request");
    problem.setType(ProblemTypes.INVALID_REQUEST);
    return problem;
  }

  /**
   * Unavailability overlapping a span the employee already declared. A conflict rather than a bad
   * request (RFC 9110 section 15.5.10): the span sent is well formed, and what refuses it is a
   * declaration already on record. The detail is a fixed sentence naming neither span, because one
   * of them is an absence somebody declared. Whoever declares a span is often the person themself,
   * so it says what they can do next, rather than speaking of them as "this employee".
   */
  @ExceptionHandler(OverlappingUnavailabilityException.class)
  public ProblemDetail handleOverlappingUnavailability(
      OverlappingUnavailabilityException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "That time overlaps a span already declared. Withdraw that one first, or choose another"
                + " time.");
    problem.setTitle("Overlaps a declared span");
    problem.setType(ProblemTypes.OVERLAPS_A_DECLARED_SPAN);
    return problem;
  }

  /**
   * A body Jackson stopped reading because it broke one of its stream constraints - the document
   * length {@code hrsched.web.max-json-document-bytes} sets, or a nesting or value-length limit -
   * is a 413 rather than a 400 (RFC 9110 section 15.5.14). Its JSON may be perfectly well formed,
   * and calling it malformed sends a client looking for a fault that is not there. Both answers are
   * fixed sentences, because Jackson's message quotes the input it failed on.
   *
   * <p>A body naming a member its request type does not have is also recorded, logged at WARN and
   * counted (ADR-058). It is bound before any use case runs, so a caller the use case would have
   * refused for their role is answered here instead, and would otherwise leave nothing behind. The
   * line names who asked and the request type, never the member or anything sent: a member is
   * whatever the caller wrote, and its value may be a person's.
   *
   * @param actor the acting account, taken from the access token as the controllers take it. Null
   *     if the principal is not an {@code Actor}: the refusal is then still answered and recorded,
   *     without one, rather than lost to a 500
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadableBody(
      HttpMessageNotReadableException exception, @AuthenticationPrincipal Actor actor) {
    if (exception.getMostSpecificCause() instanceof UnrecognizedPropertyException unknown) {
      log.atWarn()
          .addKeyValue("actor", actor == null ? null : actor.id().value())
          .addKeyValue("requestType", unknown.getReferringClass().getSimpleName())
          .log("Refused: a request body carried a member its request type does not have");
      denied("unknown_member");
    }
    if (exception.getMostSpecificCause() instanceof StreamConstraintsException) {
      ProblemDetail problem =
          ProblemDetail.forStatusAndDetail(
              HttpStatus.CONTENT_TOO_LARGE,
              "The request body exceeds a limit on its size, its nesting or the length of a value"
                  + " in it.");
      problem.setTitle("Request too large");
      problem.setType(ProblemTypes.REQUEST_TOO_LARGE);
      return problem;
    }
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, MALFORMED_BODY);
    problem.setTitle("Malformed request");
    problem.setType(ProblemTypes.MALFORMED_REQUEST);
    return problem;
  }

  /**
   * An actor whose role does not permit the act: a 403 that is logged at WARN and counted. The line
   * names the actor and the role it held.
   */
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
    problem.setType(ProblemTypes.NOT_PERMITTED);
    return problem;
  }

  /**
   * Something this organisation does not hold, answered with the sentence every context's 404
   * gives. The exceptions' own messages name the identifier asked for, and a caller is not told
   * back what it sent (ADR-024).
   */
  @ExceptionHandler({
    UnknownEmployeeException.class,
    UnknownQualificationException.class,
    UnknownDeclarationException.class
  })
  public ProblemDetail handleUnknown(RuntimeException exception) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setTitle("Not found");
    problem.setType(ProblemTypes.NOT_FOUND);
    problem.setDetail("No such resource.");
    return problem;
  }

  /**
   * The refusal the requirements call a security incident rather than a bug. The caller is answered
   * the same 404 as for something that does not exist, so it learns nothing; the incident is logged
   * at WARN and counted, because otherwise it would leave no trace at all.
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
    problem.setType(ProblemTypes.NOT_FOUND);
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
    problem.setType(ProblemTypes.NOT_PERMITTED);
    return problem;
  }
}
