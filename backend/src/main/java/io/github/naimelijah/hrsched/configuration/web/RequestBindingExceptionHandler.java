package io.github.naimelijah.hrsched.configuration.web;

import io.github.naimelijah.hrsched.HrSchedApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Answers a request Spring could not bind to a handler's parameters with a problem detail.
 *
 * <p>These failures happen before any controller runs, so none of the per-context exception
 * handlers sees them. Without this they fell through to Spring's default handling: the status was a
 * 400, but the body carried no {@code detail} a caller could show anybody. See ADR-024.
 *
 * <p>Only the three leaf types are handled, deliberately. Their parents also cover {@code
 * MissingPathVariableException} and {@code ConversionNotSupportedException}, which mean a mapping
 * of ours is wrong, and those rightly stay a 500 rather than being blamed on the caller.
 *
 * <p>The detail names the parameter and never repeats the value sent, for the reason ADR-020's
 * refusal echoes nothing. Nothing is logged or counted: a request that could not be bound is not a
 * refusal of anything the application decided (ADR-018).
 *
 * <p>Scoped to this application's controllers, so actuator's endpoints answer as they always did.
 */
@RestControllerAdvice(basePackageClasses = HrSchedApplication.class)
public class RequestBindingExceptionHandler {

  /** The title {@code ActorFromHeaders} gives a blank header, so absent and empty read the same. */
  private static final String TITLE = "Invalid request";

  /**
   * @param exception a required header that was not sent
   * @return a 400 naming the header
   */
  @ExceptionHandler(MissingRequestHeaderException.class)
  public ProblemDetail handleMissingHeader(MissingRequestHeaderException exception) {
    return invalid(exception.getHeaderName() + " is required");
  }

  /**
   * @param exception a required query parameter that was not sent
   * @return a 400 naming the parameter
   */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ProblemDetail handleMissingParameter(MissingServletRequestParameterException exception) {
    return invalid(exception.getParameterName() + " is required");
  }

  /**
   * @param exception a path variable, parameter or header whose value did not parse
   * @return a 400 naming it and the type it had to be, without the value that was sent
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ProblemDetail handleUnreadableArgument(MethodArgumentTypeMismatchException exception) {
    Class<?> required = exception.getRequiredType();
    return invalid(
        required == null
            ? exception.getName() + " could not be read"
            : exception.getName() + " could not be read as " + required.getSimpleName());
  }

  private static ProblemDetail invalid(String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    problem.setTitle(TITLE);
    return problem;
  }
}
