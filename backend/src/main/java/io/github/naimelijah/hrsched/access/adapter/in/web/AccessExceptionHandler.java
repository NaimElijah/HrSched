package io.github.naimelijah.hrsched.access.adapter.in.web;

import io.github.naimelijah.hrsched.access.domain.model.CannotResetOwnPasswordException;
import io.github.naimelijah.hrsched.access.domain.model.EmployeeAlreadyLinkedException;
import io.github.naimelijah.hrsched.access.domain.model.InvalidOrganisationNameException;
import io.github.naimelijah.hrsched.access.domain.model.LastActiveAdminException;
import io.github.naimelijah.hrsched.access.domain.model.NotAnEmployeeHereException;
import io.github.naimelijah.hrsched.access.domain.model.NotThePlatformOperatorException;
import io.github.naimelijah.hrsched.access.domain.model.SignInNameTakenException;
import io.github.naimelijah.hrsched.access.domain.model.UnacceptablePasswordException;
import io.github.naimelijah.hrsched.access.domain.model.UnknownAccountException;
import io.github.naimelijah.hrsched.access.domain.model.UnknownOrganisationException;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.NotATenantMemberException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates access failures into HTTP problem responses.
 *
 * <p>There is deliberately no catch-all, so an unexpected failure stays a 500 and stays visible.
 *
 * <p>As in the other two handlers, a cross-tenant attempt returns 404 rather than 403, because a
 * 403 would confirm that the organisation exists. Being refused as "not the operator" is a
 * different thing and is a 403: it discloses nothing, since the caller already knows whether they
 * claimed to be the operator.
 *
 * <p>It is also where these refusals are recorded, because it is the only place that sees every one
 * of them. The caller is told exactly what it was told before; only the operator gains anything.
 * See ADR-018.
 *
 * <p>It covers {@link AuthController} and {@link AccountController} as well, and for the first the
 * reason is narrower than it looks: a body Jackson cannot read is answered here with a message of
 * this handler's own, never the parser's. A parser's message quotes what it choked on, and what it
 * choked on is a request carrying a password.
 */
@RestControllerAdvice(
    assignableTypes = {OrganisationController.class, AuthController.class, AccountController.class})
public class AccessExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(AccessExceptionHandler.class);

  /** Shared with the other handlers, so one query answers "who was refused" across all of them. */
  private static final String DENIED = "hrsched.access.denied";

  private final MeterRegistry meters;

  /**
   * @param meters where refusals are counted
   */
  public AccessExceptionHandler(MeterRegistry meters) {
    this.meters = meters;
  }

  private void denied(String kind) {
    Counter.builder(DENIED)
        .description("Refused attempts, by what refused them")
        .tag("kind", kind)
        .register(meters)
        .increment();
  }

  @ExceptionHandler({InvalidOrganisationNameException.class, MalformedRequestException.class})
  public ProblemDetail handleInvalidInput(RuntimeException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problem.setTitle("Invalid request");
    return problem;
  }

  /**
   * A temporary password the policy refuses. A 422 naming the rule, because whoever typed it has to
   * know what would satisfy it; the password itself is never echoed.
   */
  @ExceptionHandler(UnacceptablePasswordException.class)
  public ProblemDetail handleUnacceptablePassword(UnacceptablePasswordException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "That temporary password does not satisfy the policy.");
    problem.setTitle("Password not accepted");
    problem.setProperty("reason", exception.reason().name());
    return problem;
  }

  /**
   * A sign-in name somebody else already holds. A 409 rather than a 400: the request was well
   * formed and the world said no.
   *
   * <p>The detail never repeats the name. Uniqueness is platform-wide, so this can be raised by a
   * collision with an account in an organisation the caller cannot see, and echoing it would tell
   * them a particular person has an account here.
   */
  @ExceptionHandler(SignInNameTakenException.class)
  public ProblemDetail handleSignInNameTaken(SignInNameTakenException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, "That sign-in name is already in use.");
    problem.setTitle("Sign-in name taken");
    return problem;
  }

  /**
   * An account named by an Admin that their organisation does not hold. A 404, and the same 404
   * whether it never existed or belongs to another customer: telling those apart would let an Admin
   * discover, one identifier at a time, that a colleague holds an account somewhere else.
   */
  @ExceptionHandler(UnknownAccountException.class)
  public ProblemDetail handleUnknownAccount(UnknownAccountException exception) {
    // Counted, like a cross-tenant attempt and for the same reason. One mistyped identifier is a
    // mistake; a hundred is somebody looking for accounts that are not theirs, and the 404 is
    // deliberately indistinguishable from "that one is somebody else's" - which is exactly what
    // makes trying the way to find out.
    log.atWarn()
        .addKeyValue("account", exception.account().value())
        .log("Refused: an account this organisation does not hold was named");
    denied("unknown_account");
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setTitle("Not found");
    problem.setDetail("No such account.");
    return problem;
  }

  /**
   * A change that would leave an organisation with no active Admin. A 409: the request was well
   * formed and the organisation said no.
   *
   * <p>Refused rather than warned about, because there is no way back. Only an Admin may restore an
   * account, and the platform operator may not act inside a tenant at all (ADR-030), so an
   * organisation that lost its last Admin would have nobody who could give it another.
   */
  @ExceptionHandler(LastActiveAdminException.class)
  public ProblemDetail handleLastActiveAdmin(LastActiveAdminException exception) {
    log.atWarn()
        .addKeyValue("account", exception.account().value())
        .log("Refused: a change would have left an organisation with no active Admin");
    denied("last_admin");
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "An organisation must keep at least one active Admin. Give somebody else that role"
                + " first.");
    problem.setTitle("Last active Admin");
    return problem;
  }

  /**
   * An Admin resetting their own password here. A 409 rather than a 403: they are permitted to
   * reset passwords, and this particular one is refused because there is a better door for it.
   */
  @ExceptionHandler(CannotResetOwnPasswordException.class)
  public ProblemDetail handleSelfReset(CannotResetOwnPasswordException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "Change your own password at /api/auth/password, which asks for the current one.");
    problem.setTitle("Not your own password");
    return problem;
  }

  /** An employee link naming somebody this organisation does not employ. */
  @ExceptionHandler(NotAnEmployeeHereException.class)
  public ProblemDetail handleNotAnEmployeeHere(NotAnEmployeeHereException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_ENTITY, "No such employee in this organisation.");
    problem.setTitle("Unknown employee");
    return problem;
  }

  /** One person, one account: the employee named already has one (ADR-012). */
  @ExceptionHandler(EmployeeAlreadyLinkedException.class)
  public ProblemDetail handleEmployeeAlreadyLinked(EmployeeAlreadyLinkedException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT, "That employee already has an account.");
    problem.setTitle("Employee already linked");
    return problem;
  }

  /**
   * Answers with a fixed sentence and never with {@code exception.getMessage()}. Jackson's message
   * quotes the input it failed on, and on the sign-in endpoint that input contains a password.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "The request body is malformed or is missing required fields.");
    problem.setTitle("Malformed request");
    return problem;
  }

  /** Administering the platform is not something a tenant can acquire by holding a role. */
  @ExceptionHandler(NotThePlatformOperatorException.class)
  public ProblemDetail handleNotTheOperator(NotThePlatformOperatorException exception) {
    log.atWarn()
        .addKeyValue("actor", exception.actor().value())
        .log("Refused: a tenant actor attempted to administer the platform");
    denied("operator");
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN, "Only the platform operator may do this.");
    problem.setTitle("Not permitted");
    return problem;
  }

  /**
   * Everything reported as absence, including a cross-tenant attempt: the response must never
   * confirm that an organisation exists to a caller who may not see it. Only the cross-tenant
   * attempt is logged and counted; an organisation that is not there is no incident.
   */
  @ExceptionHandler({UnknownOrganisationException.class, CrossTenantAccessException.class})
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
    return problem;
  }
}
