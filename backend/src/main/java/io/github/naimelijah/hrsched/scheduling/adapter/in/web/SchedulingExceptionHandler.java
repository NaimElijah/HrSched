package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.AssigneeNotQualifiedException;
import io.github.naimelijah.hrsched.scheduling.domain.model.AssigneeUnavailableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintViolation;
import io.github.naimelijah.hrsched.scheduling.domain.model.DuplicateAssignmentException;
import io.github.naimelijah.hrsched.scheduling.domain.model.InvalidConstraintException;
import io.github.naimelijah.hrsched.scheduling.domain.model.InvalidHeadcountException;
import io.github.naimelijah.hrsched.scheduling.domain.model.NoEmployeeRecordException;
import io.github.naimelijah.hrsched.scheduling.domain.model.OverlappingAssignmentException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotVerifiableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterPublicationConflictException;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftOutsideRosterPeriodException;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapAlreadyRequestedException;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapNotPendingException;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapWithThemselfException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownAssigneeException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownAssignmentException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownConstraintVersionException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRequiredQualificationException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRosterException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownShiftException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownSwapRequestException;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.InvalidTimeRangeException;
import io.github.naimelijah.hrsched.shared.domain.model.NotATenantMemberException;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>It is also where refusals are recorded, because it is the only place that sees every one of
 * them. A cross-tenant attempt in particular used to become a 404 and vanish: the requirements call
 * that a security incident rather than a bug, and an incident nothing records is an incident nobody
 * can detect. What the caller is told does not change - still a 404, still "No such resource." -
 * only what the operator can see. See ADR-018.
 */
@RestControllerAdvice(assignableTypes = {SchedulingController.class, SwapController.class})
public class SchedulingExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(SchedulingExceptionHandler.class);

  /** Shared with the workforce handler, so one query answers "who was refused" across both. */
  private static final String DENIED = "hrsched.access.denied";

  private static final String REFUSED = "hrsched.rosters.refused";

  private final MeterRegistry meters;

  /**
   * @param meters where refusals are counted
   */
  public SchedulingExceptionHandler(MeterRegistry meters) {
    this.meters = meters;
  }

  private void count(String meter, String tag, String value, String description) {
    Counter.builder(meter).description(description).tag(tag, value).register(meters).increment();
  }

  /**
   * Logged at INFO, not WARN. The rules working is not a fault: a roster that breaks them is
   * supposed to be refused, and levelling it as a problem would train whoever reads the logs to
   * ignore the level.
   */
  @ExceptionHandler(RosterNotCompliantException.class)
  public ProblemDetail handleNotCompliant(RosterNotCompliantException exception) {
    log.atInfo()
        .addKeyValue("violations", exception.violations().size())
        .log("Publication refused by the rules");
    count(REFUSED, "reason", "not_compliant", "Publications refused, by what refused them");
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    problem.setTitle("Roster breaks the rules");
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
    UnknownRequiredQualificationException.class,
    SwapWithThemselfException.class,
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
    log.warn("Publication refused: the roster names work that cannot be checked");
    count(REFUSED, "reason", "not_verifiable", "Publications refused, by what refused them");
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    problem.setTitle("Roster cannot be checked against the rules");
    return problem;
  }

  /**
   * Two publications overlapped in time as well as in period, and the schema refused the one that
   * would have left two rosters authoritative (ADR-026). A 409 rather than a 400: nothing about the
   * request was wrong, it arrived while another publication was in flight, and reloading and
   * publishing again is the remedy.
   *
   * <p>Logged at WARN, not INFO like a rule breach. Publication is meant to take turns, so reaching
   * this means something published without doing so, and that is worth somebody's attention.
   */
  @ExceptionHandler(RosterPublicationConflictException.class)
  public ProblemDetail handleConcurrentPublication(RosterPublicationConflictException exception) {
    log.atWarn()
        .addKeyValue("roster", exception.roster().value())
        .log("Publication refused by the schema: an overlapping roster was published concurrently");
    count(
        REFUSED, "reason", "concurrent_publication", "Publications refused, by what refused them");
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    problem.setTitle("Published concurrently");
    return problem;
  }

  /**
   * Asking for "my schedule" without being somebody who can be rostered. A 403 rather than an empty
   * list, because "you have no personnel record" and "you are rostered for nothing" are different
   * answers and only one of them is a configuration problem.
   */
  @ExceptionHandler(NoEmployeeRecordException.class)
  public ProblemDetail handleNoEmployeeRecord(NoEmployeeRecordException exception) {
    log.atWarn()
        .addKeyValue("actor", exception.actor().value())
        .log("Refused: an actor without a personnel record asked for their own schedule");
    count(DENIED, "kind", "no_employee_record", "Refused attempts, by what refused them");
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN, "You have no personnel record, so you have no schedule.");
    problem.setTitle("Not permitted");
    return problem;
  }

  @ExceptionHandler(NotPermittedException.class)
  public ProblemDetail handleNotPermitted(NotPermittedException exception) {
    log.atWarn()
        .addKeyValue("actor", exception.actor().value())
        .addKeyValue("role", exception.held())
        .log("Refused: the actor's role does not permit this");
    count(DENIED, "kind", "role", "Refused attempts, by what refused them");
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Your role does not permit this.");
    problem.setTitle("Not permitted");
    return problem;
  }

  /**
   * Assigning work that the domain forbids: somebody who lacks a qualification the shift demands
   * (R4), somebody who said they cannot work when it runs (F6), a shift the roster's own period
   * does not cover (R11), or somebody who would then work two shifts at once (R3), on this roster
   * or beside work they hold on another authoritative one (R35). All four earn a 422 for the same
   * reason a non-compliant roster does: the caller named real things and asked for something the
   * domain forbids, which is neither a malformed request nor a missing one.
   *
   * <p>Publication finds the same breaches in a draft that arrived carrying them, and answers
   * differently: with every breach listed, as a roster that breaks the rules (ADR-042).
   */
  @ExceptionHandler({
    AssigneeNotQualifiedException.class,
    AssigneeUnavailableException.class,
    ShiftOutsideRosterPeriodException.class,
    OverlappingAssignmentException.class
  })
  public ProblemDetail handleAssignmentRefused(RuntimeException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    problem.setTitle("Assignment refused");
    return problem;
  }

  /**
   * A swap request that is no longer waiting on anybody: decided twice, or decided after it lapsed
   * (R33). Two Schedulers with the same request open is the ordinary way the first happens, and a
   * newer roster replacing the one it names is the way the second does. Either way it is a conflict
   * rather than a bad request: nothing about what was sent was wrong, it just arrived after the
   * request had ended. Titled for both, because nobody decided a lapse.
   */
  @ExceptionHandler(SwapNotPendingException.class)
  public ProblemDetail handleNoLongerPending(SwapNotPendingException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    problem.setTitle("No longer pending");
    return problem;
  }

  /**
   * A change the roster's status forbids: editing or publishing a roster that is no longer a draft,
   * or asking for a swap against one that is not the published roster. Nothing about the request
   * was wrong; the roster is not, or is no longer, in the state it asks about. Most often somebody
   * published it first, and an edit that waited for that publication finds it so (ADR-041). The
   * caller resolves it by reloading, which makes it a conflict rather than a bad request.
   */
  @ExceptionHandler(RosterNotEditableException.class)
  public ProblemDetail handleStatusForbids(RosterNotEditableException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    problem.setTitle("Roster status forbids this");
    return problem;
  }

  /**
   * A second ask for a shift whose first is still waiting (R32). A conflict, like a second
   * decision: nothing about the ask was malformed, the assignment is simply already spoken for
   * until a Scheduler answers or its roster is replaced.
   */
  @ExceptionHandler(SwapAlreadyRequestedException.class)
  public ProblemDetail handleAlreadyRequested(SwapAlreadyRequestedException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    problem.setTitle("Already requested");
    return problem;
  }

  /**
   * Everything reported as absence, including a cross-tenant attempt: the response must never
   * confirm that a resource exists in an organisation the caller may not see.
   */
  @ExceptionHandler({
    UnknownRosterException.class,
    UnknownShiftException.class,
    UnknownAssigneeException.class,
    UnknownAssignmentException.class,
    UnknownSwapRequestException.class,
    UnknownConstraintVersionException.class,
    CrossTenantAccessException.class
  })
  public ProblemDetail handleUnknown(RuntimeException exception) {
    if (exception instanceof CrossTenantAccessException crossTenant) {
      log.atWarn()
          .addKeyValue("actor", crossTenant.actor().value())
          .addKeyValue("organisation", crossTenant.organisation().value())
          .log("Refused: an actor reached for another organisation's data");
      count(DENIED, "kind", "cross_tenant", "Refused attempts, by what refused them");
    }
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setTitle("Not found");
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
    count(DENIED, "kind", "operator_confined", "Refused attempts, by what refused them");
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN, "The platform operator may not act inside an organisation.");
    problem.setTitle("Not permitted");
    return problem;
  }
}
