package io.github.naimelijah.hrsched.configuration.security;

import io.github.naimelijah.hrsched.configuration.web.ProblemTypes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Answers an unauthenticated request with a problem detail rather than an empty 401.
 *
 * <p>Writes the {@code WWW-Authenticate} challenge RFC 9110 requires, in RFC 6750's shape, and a
 * body with it. Every other refusal in this API carries a machine-readable body; this one had no
 * reason to be the exception.
 *
 * <p>The challenge is written here rather than by Spring Security's {@code
 * BearerTokenAuthenticationEntryPoint}, because in 7.1 (checked on 7.1.2) that entry point adds a
 * {@code resource_metadata} parameter to every challenge, unconditionally: its resolver can change
 * the address but not leave it out. The address is the protected resource metadata document, which
 * {@code SecurityConfiguration} removes from the chain (issue #250). A challenge pointing at a
 * document this service refuses to serve would send a client looking for something that is not
 * there.
 *
 * <p>A refused token is described by one fixed sentence, whatever refused it. The refusal carries
 * the decoder's own message — that the token expired and when, that its signature did not verify,
 * which claim was missing — and each of those would tell somebody probing the verifier which of its
 * checks a forged token got past. None of it is sent; the caller learns only that the token was not
 * accepted, as {@code AccessTokenDecoder} promises (issue #250).
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
   * The meter every refusing class records, so one query answers "who was refused" across all of
   * it: each context's exception handler, the access {@code AuthController} and the other handler
   * in this package each declare the same name.
   */
  private static final String DENIED = "hrsched.access.denied";

  /**
   * The only {@code error_description} a refused token gets. The same sentence the token-to-actor
   * converter refuses with, so a token naming an account that does not exist reads exactly like one
   * that failed to verify.
   */
  private static final String TOKEN_NOT_ACCEPTED = "The access token was not accepted";

  /** RFC 6750's scheme, and the whole challenge when no token was presented. */
  private static final String BEARER = "Bearer";

  private final ObjectMapper json;
  private final MeterRegistry meters;

  /**
   * Creates the entry point.
   *
   * @param json writes the problem detail
   * @param meters the registry the refusal is counted in
   * @throws NullPointerException if either is {@code null}
   */
  public ProblemAuthenticationEntryPoint(ObjectMapper json, MeterRegistry meters) {
    this.json = Objects.requireNonNull(json, "json must not be null");
    this.meters = Objects.requireNonNull(meters, "meters must not be null");
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException refusal)
      throws IOException, ServletException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challengeFor(refusal));

    log.info("Refused: a request arrived without an access token this service accepts");
    Counter.builder(DENIED)
        .description("Refused attempts, by what refused them")
        .tag("kind", "unauthenticated")
        .register(meters)
        .increment();

    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
    problem.setTitle("Unauthenticated");
    problem.setType(ProblemTypes.UNAUTHENTICATED);
    problem.setDetail("A valid access token is required.");

    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(json.writeValueAsString(problem));
  }

  /**
   * The challenge for a refusal: the scheme alone when no token was presented, otherwise the scheme
   * with the error's code, description and URI. A refused token's description is always {@link
   * #TOKEN_NOT_ACCEPTED}, whatever refused it, and an empty one is among them: the resolver finds
   * the scheme with nothing after it malformed, an {@code invalid_token}. Any other error keeps its
   * own description. Spring raises {@code invalid_request} only for a token in a query or form
   * parameter, both off in this configuration, so none arrives here; one that did would be answered
   * 401, where RFC 6750 section 3.1 says 400 (ADR-050). No {@code scope} is written: it belongs to
   * {@code insufficient_scope}, a 403, which never reaches this class.
   */
  private static String challengeFor(AuthenticationException refusal) {
    if (!(refusal instanceof OAuth2AuthenticationException oauth)) {
      return BEARER;
    }
    OAuth2Error error = oauth.getError();
    String description =
        BearerTokenErrorCodes.INVALID_TOKEN.equals(error.getErrorCode())
            ? TOKEN_NOT_ACCEPTED
            : error.getDescription();
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("error", error.getErrorCode());
    if (StringUtils.hasText(description)) {
      parameters.put("error_description", description);
    }
    if (StringUtils.hasText(error.getUri())) {
      parameters.put("error_uri", error.getUri());
    }
    return parameters.entrySet().stream()
        .map(parameter -> "%s=\"%s\"".formatted(parameter.getKey(), parameter.getValue()))
        .collect(Collectors.joining(", ", BEARER + " ", ""));
  }
}
