package io.github.naimelijah.hrsched.configuration.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Specification of how the boundary check decides which requests it applies to.
 *
 * <p>Driven directly rather than through MockMvc, and that is the point. MockMvc normalises the
 * path before the filter ever sees it, so it cannot express the two shapes that matter here — a
 * percent-encoded path, and a deployment under a context path. Both were bypasses in the first
 * version of this class, and both fail <em>open</em>: the check is skipped while the request still
 * routes to its controller. A test that cannot reproduce them would be a test that proves nothing.
 */
class KnownOrganisationFilterTest {

  private static final OrganisationId KNOWN = OrganisationId.newId();

  private final KnownOrganisationFilter filter =
      new KnownOrganisationFilter(
          organisation -> organisation.equals(KNOWN),
          new ObjectMapper(),
          new SimpleMeterRegistry());

  /**
   * @param contextPath what the application is deployed under
   * @param requestUri the raw URI, exactly as a container reports it
   * @param organisation the claimed tenant
   * @return the response the filter produced
   */
  private MockHttpServletResponse filtered(
      String contextPath, String requestUri, OrganisationId organisation)
      throws ServletException, IOException {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
    request.setContextPath(contextPath);
    request.addHeader("X-Organisation", organisation.value().toString());
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    return response;
  }

  @Test
  void GivenAnUnknownOrganisation_WhenTheRequestIsPlain_ThenItIsRefused() throws Exception {
    assertThat(filtered("", "/api/employees", OrganisationId.newId()).getStatus()).isEqualTo(404);
  }

  @Test
  void GivenAKnownOrganisation_WhenTheRequestIsPlain_ThenItPassesThrough() throws Exception {
    assertThat(filtered("", "/api/employees", KNOWN).getStatus()).isEqualTo(200);
  }

  /**
   * {@code getRequestURI()} is undecoded, so keyed off it this path does not start with {@code
   * /api/} and the check is skipped — while Spring MVC, which routes on the decoded path, still
   * hands the request to the controller.
   */
  @Test
  void GivenAPercentEncodedPath_WhenItNamesAnUnknownOrganisation_ThenItIsStillRefused()
      throws Exception {
    assertThat(filtered("", "/%61pi/employees", OrganisationId.newId()).getStatus()).isEqualTo(404);
  }

  /**
   * {@code getRequestURI()} also includes the context path, so deploying under anything but the
   * root would disable the check for every request at once.
   */
  @Test
  void GivenADeploymentUnderAContextPath_WhenTheOrganisationIsUnknown_ThenItIsStillRefused()
      throws Exception {
    assertThat(filtered("/hrsched", "/hrsched/api/employees", OrganisationId.newId()).getStatus())
        .isEqualTo(404);
  }

  @Test
  void GivenADeploymentUnderAContextPath_WhenTheOrganisationIsKnown_ThenItPassesThrough()
      throws Exception {
    assertThat(filtered("/hrsched", "/hrsched/api/employees", KNOWN).getStatus()).isEqualTo(200);
  }

  @Test
  void GivenSomethingOutsideTheApi_WhenItCarriesAStrayHeader_ThenItIsLeftAlone() throws Exception {
    assertThat(filtered("", "/actuator/health", OrganisationId.newId()).getStatus()).isEqualTo(200);
  }

  @Test
  void GivenOnboarding_WhenItClaimsAnUnknownOrganisation_ThenItIsExempt() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/organisations");
    request.addHeader("X-Organisation", UUID.randomUUID().toString());
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, new MockFilterChain());
    assertThat(response.getStatus()).isEqualTo(200);
  }
}
