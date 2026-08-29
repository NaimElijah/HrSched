package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.InvalidTimeRangeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates rejected requests into HTTP problem responses.
 *
 * <p>The translation belongs in the adapter, not the domain: the domain says a period is invalid,
 * and only the transport knows that this means 400. Nothing is swallowed — each handler enriches a
 * specific exception type. There is deliberately no catch-all, so an unexpected failure stays a 500
 * and stays visible.
 */
@RestControllerAdvice
public class ShiftExceptionHandler {

  /**
   * @param exception the rule violation raised by the domain
   * @return an RFC 9457 problem detail with status 400
   */
  @ExceptionHandler(InvalidTimeRangeException.class)
  public ProblemDetail handleInvalidTimeRange(InvalidTimeRangeException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problem.setTitle("Invalid shift period");
    return problem;
  }

  /**
   * Handles a body that is malformed or missing required fields. Spring maps this to 400 by
   * default; handling it explicitly gives the caller the same problem-detail shape as every other
   * rejection.
   *
   * @param exception the deserialization failure
   * @return an RFC 9457 problem detail with status 400
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "The request body is malformed or is missing required fields.");
    problem.setTitle("Malformed request");
    return problem;
  }
}
