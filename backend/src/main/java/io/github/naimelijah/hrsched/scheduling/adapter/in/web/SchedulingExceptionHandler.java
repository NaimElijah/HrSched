package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintViolation;
import io.github.naimelijah.hrsched.scheduling.domain.model.DuplicateAssignmentException;
import io.github.naimelijah.hrsched.scheduling.domain.model.InvalidConstraintException;
import io.github.naimelijah.hrsched.scheduling.domain.model.InvalidHeadcountException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException;
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
 * for whom, because a scheduler told only "rejected" cannot act on it.
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

  private static List<String> describe(List<ConstraintViolation> violations) {
    return violations.stream()
        .map(
            violation ->
                "%s: %s (%s)"
                    .formatted(
                        violation.employee().value(), violation.constraint(), violation.detail()))
        .toList();
  }

  @ExceptionHandler({
    InvalidTimeRangeException.class,
    InvalidHeadcountException.class,
    InvalidConstraintException.class,
    DuplicateAssignmentException.class,
    RosterNotEditableException.class
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
