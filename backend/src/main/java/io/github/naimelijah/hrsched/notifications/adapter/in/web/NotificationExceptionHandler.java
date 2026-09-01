package io.github.naimelijah.hrsched.notifications.adapter.in.web;

import io.github.naimelijah.hrsched.notifications.domain.model.NotAddressableException;
import io.github.naimelijah.hrsched.notifications.domain.model.UnknownNotificationException;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates notification failures into HTTP problem responses.
 *
 * <p>There is deliberately no catch-all, so an unexpected failure stays a 500 and stays visible.
 *
 * <p>A notification belonging to somebody else earns the same 404 as one that never existed, on the
 * same reasoning as a cross-tenant attempt: the response must not confirm that the resource is
 * real.
 */
@RestControllerAdvice(assignableTypes = NotificationController.class)
public class NotificationExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(NotificationExceptionHandler.class);

  /** Shared with the other handlers, so one query answers "who was refused" across all of them. */
  private static final String DENIED = "hrsched.access.denied";

  private final MeterRegistry meters;

  /**
   * @param meters where refusals are counted
   */
  public NotificationExceptionHandler(MeterRegistry meters) {
    this.meters = meters;
  }

  private void denied(String kind) {
    Counter.builder(DENIED)
        .description("Refused attempts, by what refused them")
        .tag("kind", kind)
        .register(meters)
        .increment();
  }

  @ExceptionHandler(MalformedRequestException.class)
  public ProblemDetail handleInvalidInput(RuntimeException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problem.setTitle("Invalid request");
    return problem;
  }

  @ExceptionHandler(NotAddressableException.class)
  public ProblemDetail handleNotAddressable(NotAddressableException exception) {
    log.atWarn()
        .addKeyValue("actor", exception.actor().value())
        .log("Refused: an actor without a personnel record asked for their notifications");
    denied("no_employee_record");
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN, "You have no personnel record, so you have no notifications.");
    problem.setTitle("Not permitted");
    return problem;
  }

  @ExceptionHandler({UnknownNotificationException.class, CrossTenantAccessException.class})
  public ProblemDetail handleUnknown(RuntimeException exception) {
    if (exception instanceof CrossTenantAccessException crossTenant) {
      log.atWarn()
          .addKeyValue("actor", crossTenant.actor().value())
          .addKeyValue("organisation", crossTenant.organisation().value())
          .log("Refused: an actor reached for another organisation's data");
      denied("cross_tenant");
    }
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setTitle("Not found");
    problem.setDetail("No such resource.");
    return problem;
  }
}
