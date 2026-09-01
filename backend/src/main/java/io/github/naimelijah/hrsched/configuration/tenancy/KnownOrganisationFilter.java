package io.github.naimelijah.hrsched.configuration.tenancy;

import io.github.naimelijah.hrsched.access.application.port.in.KnownOrganisationsUseCase;
import io.github.naimelijah.hrsched.configuration.observability.TraceIdResponseFilter;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.ObjectMapper;

/**
 * Refuses a request that claims an organisation nobody has onboarded.
 *
 * <p>Before this existed, {@code X-Organisation} was believed on sight. Any caller could invent a
 * tenant by generating a UUID, and every repository would happily create and read data under it —
 * the README's own quick start did exactly that. Tenancy was asserted by the caller rather than
 * checked against anything, which is the opposite of what ADR-009 claims the system does.
 *
 * <p><strong>This is not authentication and does not make the header placeholder safe.</strong> It
 * establishes that a claimed tenant is one of ours, not that the caller belongs to it. Anyone who
 * learns a real organisation identifier can still claim any role inside it. ADR-011 is still owed;
 * see ADR-020.
 *
 * <p>Three things it deliberately does not do:
 *
 * <ul>
 *   <li><strong>A missing header passes through.</strong> Whether the operation needs one is the
 *       controller's business, and it answers 400 with a message naming the header.
 *   <li><strong>A malformed header passes through.</strong> {@code ActorFromHeaders} already turns
 *       an unparseable identifier into a 400, and a 404 here would be a worse answer to a plainly
 *       broken request.
 *   <li><strong>Onboarding is exempt.</strong> Creating the first organisation cannot require an
 *       organisation to already exist, or the platform can never be bootstrapped.
 * </ul>
 *
 * <p>The refusal is shaped exactly like the 404 an unknown record produces — same status, same
 * title, same detail — and it echoes nothing the caller sent. That keeps it from <em>adding</em> a
 * signal beyond the status itself, and it is the most this can honestly claim: a known organisation
 * answers differently from an unknown one, so this is an oracle for whether a given identifier
 * names a real tenant. What makes that acceptable is arithmetic rather than design — an
 * organisation identifier is a random version-4 UUID, so guessing one is 2^122 attempts, and the
 * alternative was believing every identifier equally. It is recorded rather than defended in
 * ADR-020.
 *
 * <p>It lives in {@code configuration} rather than in the three web adapters because it is one rule
 * about tenancy rather than three copies of adapter plumbing, and because {@code configuration} is
 * already the one package permitted to see more than one context. It also outlives ADR-011: whether
 * a tenant exists is a different question from who the caller is.
 */
@Component
@Order(KnownOrganisationFilter.ORDER)
public class KnownOrganisationFilter extends OncePerRequestFilter {

  /**
   * Immediately after {@code TraceIdResponseFilter}, so a refusal written here still carries {@code
   * X-Trace-Id} and can be quoted by the person who received it. Early otherwise: a request naming
   * an organisation that does not exist should reach as little of the application as possible.
   */
  static final int ORDER = TraceIdResponseFilter.ORDER + 1;

  private static final String ORGANISATION_HEADER = "X-Organisation";

  /** Only the API is tenant-scoped. Actuator has no tenant and must stay reachable regardless. */
  private static final String API = "/api/";

  /** The bootstrap path: onboarding cannot presuppose an organisation. */
  private static final String ONBOARDING = "/api/organisations";

  /** Shared with the three exception handlers, so one query answers "who was refused". */
  private static final String DENIED = "hrsched.access.denied";

  /**
   * Resolves the path the way Spring MVC resolves it: decoded, and relative to the context path.
   *
   * <p>Not {@code getRequestURI()}, which was the first version and was wrong in two ways that both
   * fail <em>open</em>. It returns the raw, undecoded URI, so {@code /%61pi/employees} does not
   * start with {@code /api/} and skips this check — while the decoded path still routes to the
   * controller, re-opening the hole this class exists to close. It also includes the context path,
   * so deploying under anything but the root would disable the check for every request at once.
   */
  private static final UrlPathHelper PATHS = UrlPathHelper.defaultInstance;

  private static final Logger log = LoggerFactory.getLogger(KnownOrganisationFilter.class);

  private final KnownOrganisationsUseCase organisations;
  private final ObjectMapper json;
  private final MeterRegistry meters;

  /**
   * @param organisations the register a claimed tenant is checked against
   * @param json the mapper the rest of the API renders with, so this body matches the others
   * @param meters where refusals are counted
   */
  public KnownOrganisationFilter(
      KnownOrganisationsUseCase organisations, ObjectMapper json, MeterRegistry meters) {
    this.organisations = organisations;
    this.json = json;
    this.meters = meters;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    UUID claimed = organisationClaimedBy(request);
    if (claimed == null || organisations.isKnown(new OrganisationId(claimed))) {
      chain.doFilter(request, response);
      return;
    }
    refuse(response, claimed);
  }

  /**
   * @param request the request being filtered
   * @return the organisation this request claims and that is worth checking, or {@code null} if
   *     there is nothing here for this filter to decide
   */
  private static UUID organisationClaimedBy(HttpServletRequest request) {
    String path = PATHS.getPathWithinApplication(request);
    if (!path.startsWith(API) || isOnboarding(request, path)) {
      return null;
    }
    String header = request.getHeader(ORGANISATION_HEADER);
    if (header == null || header.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(header.trim());
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }

  private static boolean isOnboarding(HttpServletRequest request, String path) {
    return ONBOARDING.equals(path) && HttpMethod.POST.matches(request.getMethod());
  }

  /**
   * Writes the same 404 an unknown record produces, and records that it happened.
   *
   * <p>The exception handlers cannot do this: a filter rejects the request before any handler is
   * chosen, so no {@code @RestControllerAdvice} ever sees it. That is also why the counter and the
   * log line are written here rather than inherited.
   */
  private void refuse(HttpServletResponse response, UUID claimed) throws IOException {
    log.atWarn()
        .addKeyValue("organisation", claimed)
        .log("Refused: a request claimed an organisation that does not exist");
    Counter.builder(DENIED)
        .description("Refused attempts, by what refused them")
        .tag("kind", "unknown_organisation")
        .register(meters)
        .increment();

    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    problem.setTitle("Not found");
    problem.setDetail("No such resource.");

    response.setStatus(HttpStatus.NOT_FOUND.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(json.writeValueAsString(problem));
  }
}
