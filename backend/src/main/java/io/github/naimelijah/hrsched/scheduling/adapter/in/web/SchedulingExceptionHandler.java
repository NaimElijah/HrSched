package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintViolation;
import io.github.naimelijah.hrsched.scheduling.domain.model.DuplicateAssignmentException;
import io.github.naimelijah.hrsched.scheduling.domain.model.InvalidConstraintException;
import io.github.naimelijah.hrsched.scheduling.domain.model.InvalidHeadcountException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotVerifiableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRequiredQualificationException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRosterException;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.InvalidTimeRangeException;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates scheduling failures into HTTP problem responses.
 *
 * <p>The interesting one is a non-compliant roster. It returns 422 rather than 400: the request was
 * perfectly well formed, and what failed was the rules. The response lists every rule broken and
 * for whom, as structured {@link ViolationResponse} objects rather than prose, because a scheduler
 * told only "rejected" cannot act on it and a caller should not have to parse a sentence to show
 * one.
 *
 * <p>There is no override anywhere here. A roster that breaks the rules is not published, and the
 * remedy is a different roster.
 */
@RestControllerAdvice(assignableTypes = SchedulingController.class)
public class SchedulingExceptionHandler {

  @ExceptionHandler(RosterNotCompliantException.class)
  public ProblemDetail handleNotCompliant(RosterNotCompliantException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    problem.setTitle("Roster breaks the working-time rules");
    problem.setProperty("violations", describe(exception.violations()));
    return problem;
  }

  private static List<ViolationResponse> describe(List<ConstraintViolation> violations) {
    return violations.stream().map(ViolationResponse::from).toList();
  }

  @ExceptionHandler({
    InvalidTimeRangeException.class,
    InvalidHeadcountException.class,
    InvalidConstraintException.class,
    DuplicateAssignmentException.class,
    RosterNotEditableException.class,
    UnknownRequiredQualificationException.class,
    MalformedRequestException.class
  })
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

  /**
   * A roster naming a shift that cannot be resolved is refused, not published with that work
   * unchecked. Like a rule breach it is a 422: the request was well formed, the roster is not
   * publishable.
   */
  @ExceptionHandler(RosterNotVerifiableException.class)
  public ProblemDetail handleNotVerifiable(RosterNotVerifiableException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    problem.setTitle("Roster cannot be checked against the rules");
    return problem;
  }

  @ExceptionHandler(NotPermittedException.class)
  public ProblemDetail handleNotPermitted(NotPermittedException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Your role does not permit this.");
    problem.setTitle("Not permitted");
    return problem;
  }

  /** A cross-tenant attempt is a 404, so the response never confirms that the resource exists. */
  @ExceptionHandler({UnknownRosterException.class, CrossTenantAccessException.class})
  public ProblemDetail handleUnknown(RuntimeException exception) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setTitle("Not found");
    problem.setDetail("No such resource.");
    return problem;
  }
}
