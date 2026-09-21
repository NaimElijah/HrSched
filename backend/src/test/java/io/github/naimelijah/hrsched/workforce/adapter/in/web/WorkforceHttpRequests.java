package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Request scaffolding shared by the workforce HTTP tests.
 *
 * <p>Extracted rather than duplicated because both callers live in this package, so a shared helper
 * crosses no boundary. That is the difference from {@code ActorFromHeaders} and its test, which
 * stay duplicated per context on purpose: sharing those would make one context's adapter package
 * depend on another's.
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
   * <p>It is really onboarded rather than invented. Since ADR-020 an organisation nobody onboarded
   * does not exist, and every request naming one is refused with a 404 before it reaches a
   * controller — so a random UUID here would fail every test that used it, for the right reason.
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
