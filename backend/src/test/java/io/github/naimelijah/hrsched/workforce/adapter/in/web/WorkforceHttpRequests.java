package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Request scaffolding shared by the workforce HTTP tests.
 *
 * <p>Extracted rather than duplicated because both callers live in this package, so a shared helper
 * crosses no boundary. Both request builders now delegate to {@code TestTenants}: since the
 * identity comes from a token, minting one needs the Spring context that signed it, and there is no
 * per-context version of that.
 *
 * <p>Not named {@code *Test}, so surefire does not try to run it — the same arrangement as {@code
 * architecture.ContextPackages}.
 */
final class WorkforceHttpRequests {

  private WorkforceHttpRequests() {}

  /**
   * A fresh organisation per test. The repositories behind the shared Spring context are
   * singletons, so a shared identifier would let one test's data decide another test's assertion.
   *
   * <p>It is really onboarded rather than invented. The tenant accounts these tests act as are
   * created in this organisation, and {@code account.organisation_id} must reference one that
   * exists (V3) — so a random UUID here would fail every test that used it, for the right reason.
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
}
