package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Request scaffolding shared by the scheduling HTTP tests.
 *
 * <p>Extracted rather than duplicated because both callers live in this package, so a shared helper
 * crosses no boundary — the same arrangement {@code WorkforceHttpRequests} uses. Both request
 * builders now delegate to {@code TestTenants}: since the identity comes from a token, minting one
 * needs the Spring context that signed it, and there is no per-context version of that.
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
   * would let one test's data decide another test's assertion; and the tenant accounts these tests
   * act as are created in this organisation, whose identity {@code account.organisation_id} must
   * reference (V3), so an invented identifier would fail in the fixture.
   *
   * @param mockMvc the client to onboard through
   * @return the identifier of an organisation nothing else in the suite is using
   * @throws Exception if onboarding fails, which would make every assertion after it meaningless
   */
  static String anOrganisation(MockMvc mockMvc) throws Exception {
    String body =
        mockMvc
            .perform(
                TestTenants.with(post("/api/organisations"), TestTenants.operatorToken(mockMvc))
                    .content(
                        TestTenants.onboarding("Organisation %s".formatted(UUID.randomUUID()))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  /**
   * @param mockMvc the client whose Spring context issues the token
   * @param request the request being built
   * @param organisation the organisation being acted in
   * @param role the role to act with
   * @return the request, carrying a token for an account of that organisation and role
   */
  static MockHttpServletRequestBuilder as(
      MockMvc mockMvc, MockHttpServletRequestBuilder request, String organisation, String role)
      throws Exception {
    return TestTenants.as(mockMvc, request, organisation, role);
  }

  /**
   * As {@link #as}, but as the account linked to a particular personnel record — which is what
   * makes self-service reachable at all (ADR-012).
   *
   * @param mockMvc the client whose Spring context issues the token
   * @param request the request being built
   * @param organisation the organisation being acted in
   * @param employee the personnel record the caller is
   * @return the request, carrying a token for that person's account
   */
  static MockHttpServletRequestBuilder asThemself(
      MockMvc mockMvc, MockHttpServletRequestBuilder request, String organisation, String employee)
      throws Exception {
    return TestTenants.asThemself(mockMvc, request, organisation, employee);
  }

  /**
   * Generates a draft over HTTP the way the SPA does (ADR-046): start it, answered 202, then read
   * how it ended. The suite runs each generation on the requesting thread, so it has ended by then.
   *
   * @param mockMvc the client whose Spring context issues the token
   * @param organisation the organisation generating
   * @param start the span's start, an ISO-8601 instant
   * @param end the span's end
   * @return the draft's identity
   * @throws AssertionError if the generation did not end with a draft
   */
  static String generateDraft(MockMvc mockMvc, String organisation, String start, String end)
      throws Exception {
    String started =
        mockMvc
            .perform(
                as(mockMvc, post("/api/rosters/generations"), organisation, "SCHEDULER")
                    .content("{\"start\":\"%s\",\"end\":\"%s\"}".formatted(start, end)))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String ended =
        mockMvc
            .perform(
                as(
                    mockMvc,
                    get("/api/rosters/generations/" + JsonPath.read(started, "$.id")),
                    organisation,
                    "SCHEDULER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(ended, "$.roster");
  }
}
