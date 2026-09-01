package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Request scaffolding shared by the scheduling HTTP tests.
 *
 * <p>Extracted rather than duplicated because both callers live in this package, so a shared helper
 * crosses no boundary — the same arrangement {@code WorkforceHttpRequests} uses, and the same
 * distinction from {@code ActorFromHeaders}, which stays duplicated per context on purpose.
 *
 * <p>The onboarding step is the reason this is worth sharing: it encodes what a caller must do
 * before anything else works, and when that contract moves, one place changes rather than several
 * that each fail as a mystery 400 in an unrelated suite.
 *
 * <p>Not named {@code *Test}, so surefire does not try to run it.
 */
final class SchedulingHttpRequests {

  private SchedulingHttpRequests() {}

  /**
   * A fresh organisation per test, really onboarded rather than invented.
   *
   * <p>The repositories behind the shared Spring context are singletons, so a shared identifier
   * would let one test's data decide another test's assertion; and since ADR-020 a random UUID does
   * not name an organisation at all, so every request using one would be refused.
   *
   * @param mockMvc the client to onboard through
   * @return the identifier of an organisation nothing else in the suite is using
   * @throws Exception if onboarding fails, which would make every assertion after it meaningless
   */
  static String anOrganisation(MockMvc mockMvc) throws Exception {
    String body =
        mockMvc
            .perform(
                post("/api/organisations")
                    .header("X-User", UUID.randomUUID().toString())
                    .header("X-Role", "OPERATOR")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Organisation %s\"}".formatted(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  /**
   * @param request the request being built
   * @param organisation the organisation being acted on
   * @param role the role being claimed
   * @return the request, carrying a complete acting identity
   */
  static MockHttpServletRequestBuilder as(
      MockHttpServletRequestBuilder request, String organisation, String role) {
    return request
        .header("X-Organisation", organisation)
        .header("X-User", UUID.randomUUID().toString())
        .header("X-Role", role)
        .contentType(MediaType.APPLICATION_JSON);
  }

  /**
   * As {@link #as}, but claiming to be a particular personnel record — which is what makes
   * self-service reachable at all (ADR-012).
   *
   * @param request the request being built
   * @param organisation the organisation being acted on
   * @param employee the personnel record the caller claims to be
   * @return the request, carrying an employee-linked acting identity
   */
  static MockHttpServletRequestBuilder asThemself(
      MockHttpServletRequestBuilder request, String organisation, String employee) {
    return as(request, organisation, "EMPLOYEE").header("X-Employee", employee);
  }
}
