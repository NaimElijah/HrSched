package io.github.naimelijah.hrsched.configuration.security;

import io.github.naimelijah.hrsched.configuration.web.ProblemTypes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Answers an authenticated caller who may not do this with a problem detail.
 *
 * <p>Distinguishes one case, and only one: a caller whose password change is still pending is told
 * so, in a problem of its own kind, titled "Password change required", so a client can tell it from
 * a role refusal without reading the sentence (ADR-057). That is not a leak — they already know
 * they were given a temporary password — and without it every screen would refuse them with a
 * message about their role, which is not the reason and would send them looking for an
 * administrator instead of a password field.
 *
 * <p>Everything else is the same 403 with the same sentence. Which rule refused a caller is a fact
 * about how this API is arranged, and there is no reason to hand it out.
 *
 * <p>A role refusal is titled "Not permitted" and typed {@code not-permitted}, as every context's
 * handler titles and types the same refusal, so a client tells it apart the same way whether the
 * chain or a use case made it. A handler's 403 also carries {@code instance}, the path refused,
 * which Spring fills in for a problem an advice returns; this one, written in a filter, has none.
 *
 * <p>Runs in a filter, before any controller is chosen, so the exception handlers never see it.
 */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

  private static final Logger log = LoggerFactory.getLogger(ProblemAccessDeniedHandler.class);

  /**
   * Shared with the exception handlers, so one query answers "who was refused" across all of it.
   */
  private static final String DENIED = "hrsched.access.denied";

  /** Mirrors the authority the token adapter grants while a temporary password is pending. */
  static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";

  private final ObjectMapper json;
  private final MeterRegistry meters;

  public ProblemAccessDeniedHandler(ObjectMapper json, MeterRegistry meters) {
    this.json = Objects.requireNonNull(json, "json must not be null");
    this.meters = Objects.requireNonNull(meters, "meters must not be null");
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException refusal)
      throws IOException {
    Authentication caller = SecurityContextHolder.getContext().getAuthentication();
    boolean pendingChange = hasOnlyPendingPasswordChange(caller);

    log.atWarn()
        .addKeyValue("actor", caller == null ? null : caller.getName())
        .log("Refused: an authenticated caller may not do this");
    Counter.builder(DENIED)
        .description("Refused attempts, by what refused them")
        .tag("kind", pendingChange ? "password_change_required" : "route")
        .register(meters)
        .increment();

    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
    if (pendingChange) {
      problem.setTitle("Password change required");
      problem.setType(ProblemTypes.PASSWORD_CHANGE_REQUIRED);
      problem.setDetail("Change your temporary password first.");
    } else {
      problem.setTitle("Not permitted");
      problem.setType(ProblemTypes.NOT_PERMITTED);
      problem.setDetail("Your role does not permit this.");
    }

    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(json.writeValueAsString(problem));
  }

  private static boolean hasOnlyPendingPasswordChange(Authentication caller) {
    return caller != null
        && caller.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .allMatch(PASSWORD_CHANGE_REQUIRED::equals)
        && !caller.getAuthorities().isEmpty();
  }
}
