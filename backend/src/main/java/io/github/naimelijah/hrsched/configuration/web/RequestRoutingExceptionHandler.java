package io.github.naimelijah.hrsched.configuration.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Answers the refusals Spring MVC makes on its own with a problem detail (issue #275).
 *
 * <p>A path no controller maps, a method the mapped path does not take, a body type the route does
 * not read and an {@code Accept} it cannot answer are refused by Spring itself, mostly while it is
 * still choosing a handler, so none of the contexts' handlers sees them. Left to Spring's default
 * resolution each answered with a status and no body a caller could show anybody.
 *
 * <p>Unscoped, unlike {@link RequestBindingExceptionHandler}, because it has to be: a request
 * refused while a handler is being chosen has no handler, and an advice scoped to some controllers
 * is never consulted for it. So it answers on every path, actuator's included. It handles exactly
 * these four types, which no other advice in the application handles, so which advice answers never
 * depends on the order they are registered in (ADR-024). Spring's own {@code
 * ResponseEntityExceptionHandler} is not used, as its single handler method also claims {@code
 * HttpMessageNotReadableException} and the binding failures the other advices answer.
 *
 * <p>Each detail is a fixed sentence that repeats nothing sent: neither the method, nor the media
 * type, nor the path. The headers Spring attaches to the refusal are kept, so a 405 still names the
 * methods the route does take in {@code Allow}, as RFC 9110 section 15.5.6 requires. The media type
 * is set here rather than chosen from {@code Accept}: Spring writes no body at all for an {@code
 * Accept} it cannot parse, which a 406 is exactly the answer to. Nothing is logged or counted: none
 * of these is a refusal of anything the application decided (ADR-018).
 */
@RestControllerAdvice
public class RequestRoutingExceptionHandler {

  /** The detail every 404 in the API gives, whatever was asked for. */
  private static final String NO_SUCH_RESOURCE = "No such resource.";

  /**
   * @param exception a request to a mapped path, in a method none of its handlers takes
   * @return a 405 carrying the route's {@code Allow}
   */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMethodNotAllowed(
      HttpRequestMethodNotSupportedException exception) {
    return refusal(
        exception,
        HttpStatus.METHOD_NOT_ALLOWED,
        ProblemTypes.METHOD_NOT_ALLOWED,
        "Method not allowed",
        "This route does not take that method.");
  }

  /**
   * @param exception a request whose {@code Accept} names no type the route can answer in
   * @return a 406, written as a problem detail whatever was accepted
   */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<ProblemDetail> handleNotAcceptable(
      HttpMediaTypeNotAcceptableException exception) {
    return refusal(
        exception,
        HttpStatus.NOT_ACCEPTABLE,
        ProblemTypes.NOT_ACCEPTABLE,
        "Not acceptable",
        "This route cannot answer in a media type the request accepts.");
  }

  /**
   * @param exception a body of a type the route does not read, or one whose type does not parse
   * @return a 415
   */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException exception) {
    return refusal(
        exception,
        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
        ProblemTypes.UNSUPPORTED_MEDIA_TYPE,
        "Unsupported media type",
        "This route does not take a body of that media type.");
  }

  /**
   * @param exception a path no handler maps
   * @return the 404 every absence in the API gets
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ProblemDetail> handleNoSuchPath(NoResourceFoundException exception) {
    return refusal(
        exception, HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "Not found", NO_SUCH_RESOURCE);
  }

  private static ResponseEntity<ProblemDetail> refusal(
      ErrorResponse exception, HttpStatus status, URI type, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(type);
    problem.setTitle(title);
    return ResponseEntity.status(status)
        .headers(exception.getHeaders())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(problem);
  }
}
