package io.github.naimelijah.hrsched.adapter.in.web;

import io.github.naimelijah.hrsched.domain.model.InvalidShiftPeriodException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates domain rule violations into HTTP problem responses.
 *
 * <p>The translation belongs in the adapter, not the domain: the domain says a period is invalid,
 * and only the transport knows that this means 400. Nothing is swallowed — the exception's own
 * message becomes the problem detail.
 */
@RestControllerAdvice
public class ShiftExceptionHandler {

  /**
   * @param exception the rule violation raised by the domain
   * @return an RFC 9457 problem detail with status 400
   */
  @ExceptionHandler(InvalidShiftPeriodException.class)
  public ProblemDetail handleInvalidShiftPeriod(InvalidShiftPeriodException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problem.setTitle("Invalid shift period");
    return problem;
  }
}
