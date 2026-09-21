package io.github.naimelijah.hrsched.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Specification of the answer to a request Spring cannot bind to a handler's parameters.
 *
 * <p>A missing query parameter, or an identifier that does not parse, fails before any controller
 * runs, so none of the per-context exception handlers ever sees it. A missing header used to be
 * here too; there are no identity headers left to omit. The status was always right; the body was
 * not a problem detail, so a caller reading {@code detail} found nothing to show a person. See
 * ADR-024.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class RequestBindingHttpTest {

  /** Sent where a value is malformed, so a test can prove the body does not repeat it. */
  private static final String UNMISTAKABLE = "xyzzy-not-a-value";

  @Autowired private MockMvc mockMvc;

  private String anOrganisation() throws Exception {
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

  private MockHttpServletRequestBuilder as(
      MockHttpServletRequestBuilder request, String organisation, String role) throws Exception {
    return TestTenants.as(mockMvc, request, organisation, role);
  }

  @Test
  void GivenNoSpan_WhenListingShifts_ThenTheProblemNamesTheParameter() throws Exception {
    mockMvc
        .perform(
            as(get("/api/shifts"), anOrganisation(), "SCHEDULER")
                .param("to", "2026-10-12T00:00:00Z"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("from is required"));
  }

  @Test
  void GivenAFromThatIsNotAnInstant_WhenListingShifts_ThenNothingSentIsEchoed() throws Exception {
    String body =
        mockMvc
            .perform(
                as(get("/api/shifts"), anOrganisation(), "SCHEDULER")
                    .param("from", UNMISTAKABLE)
                    .param("to", "2026-10-12T00:00:00Z"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("from could not be read as Instant"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(UNMISTAKABLE);
  }

  /**
   * What the server says about the value repeats none of it. The path itself still comes back as
   * {@code instance}, as it does on every problem detail Spring writes in this application: that is
   * the address the caller asked for, not a claim about what they sent, so the body as a whole is
   * not asserted here the way it is for a query parameter above.
   */
  @Test
  void GivenARosterIdentifierThatIsNotAUuid_WhenReadingIt_ThenTheDetailRepeatsNothingSent()
      throws Exception {
    mockMvc
        .perform(as(get("/api/rosters/" + UNMISTAKABLE), anOrganisation(), "SCHEDULER"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Invalid request"))
        .andExpect(jsonPath("$.detail").value("id could not be read as UUID"));
  }
}
