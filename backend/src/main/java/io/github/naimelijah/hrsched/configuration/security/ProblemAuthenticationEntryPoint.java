package io.github.naimelijah.hrsched.configuration.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.ServletException;
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
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Answers an unauthenticated request with a problem detail rather than an empty 401.
 *
 * <p>Delegates the headers to Spring Security's own entry point first, so the {@code
 * WWW-Authenticate} challenge RFC 9110 requires is exactly the one the resource server would have
 * written, and then supplies the body it leaves empty. Every other refusal in this API carries a
 * machine-readable body; this one had no reason to be the exception.
 *
 * <p>Logged at INFO with no fields. An expired token is the most ordinary thing that happens here —
 * every client hits this on the way to renewing — so WARN would drown the refusals that matter, and
 * a field naming the caller would be recording an identity nobody has proved.
 *
 * <p>This runs in a filter, before any controller is chosen, so no {@code @RestControllerAdvice}
 * ever sees it. That is why the body, the counter and the log line are written here rather than
 * inherited from the exception handlers.
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private static final Logger log = LoggerFactory.getLogger(ProblemAuthenticationEntryPoint.class);

  /**
   * Shared with the exception handlers, so one query answers "who was refused" across all of it.
   */
  private static final String DENIED = "hrsched.access.denied";

  private final AuthenticationEntryPoint challenge = new BearerTokenAuthenticationEntryPoint();
  private final ObjectMapper json;
  private final MeterRegistry meters;

  public ProblemAuthenticationEntryPoint(ObjectMapper json, MeterRegistry meters) {
    this.json = Objects.requireNonNull(json, "json must not be null");
    this.meters = Objects.requireNonNull(meters, "meters must not be null");
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException refusal)
      throws IOException, ServletException {
    challenge.commence(request, response, refusal);

    log.info("Refused: a request arrived without an access token this service accepts");
    Counter.builder(DENIED)
        .description("Refused attempts, by what refused them")
        .tag("kind", "unauthenticated")
        .register(meters)
        .increment();

    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
    problem.setTitle("Unauthenticated");
    problem.setDetail("A valid access token is required.");

    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(json.writeValueAsString(problem));
  }
}
