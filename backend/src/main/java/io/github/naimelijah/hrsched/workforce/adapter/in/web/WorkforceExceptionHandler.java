package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.InvalidTimeRangeException;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.workforce.domain.model.InvalidContractTermsException;
import io.github.naimelijah.hrsched.workforce.domain.model.InvalidEmployeeNameException;
import io.github.naimelijah.hrsched.workforce.domain.model.InvalidQualificationNameException;
import io.github.naimelijah.hrsched.workforce.domain.model.OverlappingUnavailabilityException;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownEmployeeException;
import io.github.naimelijah.hrsched.workforce.domain.model.UnknownQualificationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates workforce failures into HTTP problem responses.
 *
 * <p>Only the domain knows a rule was broken; only the transport knows what status that deserves.
 * There is deliberately no catch-all, so an unexpected failure stays a 500 and stays visible.
 *
 * <p>Note what a cross-tenant attempt returns: 404, not 403. A 403 would confirm that the resource
 * exists in some other organisation, which is itself a small leak. The caller is told only that
 * there is nothing here for them.
 */
@RestControllerAdvice(assignableTypes = {EmployeeController.class, QualificationController.class})
public class WorkforceExceptionHandler {

  @ExceptionHandler({
    InvalidEmployeeNameException.class,
    InvalidQualificationNameException.class,
    InvalidContractTermsException.class,
    InvalidTimeRangeException.class,
    OverlappingUnavailabilityException.class
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

  @ExceptionHandler({UnknownEmployeeException.class, UnknownQualificationException.class})
  public ProblemDetail handleUnknown(RuntimeException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    problem.setTitle("Not found");
    return problem;
  }

  @ExceptionHandler(CrossTenantAccessException.class)
  public ProblemDetail handleCrossTenant(CrossTenantAccessException exception) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setTitle("Not found");
    problem.setDetail("No such resource.");
    return problem;
  }
}
