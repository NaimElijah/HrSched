package io.github.naimelijah.hrsched.notifications.adapter.in.web;

import io.github.naimelijah.hrsched.notifications.domain.model.NotAddressableException;
import io.github.naimelijah.hrsched.notifications.domain.model.UnknownNotificationException;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.NotATenantMemberException;
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
    problem.setType(ProblemTypes.NOT_PERMITTED);
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
