package io.github.naimelijah.hrsched.configuration.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Specifies the one piece of the trace that a caller can actually see.
 *
 * <p>A refusal carrying the identifier is the case that matters: the person who can report a
 * problem is the person who was refused, and without this they have nothing to quote.
 *
 * <p>Annotated identically to the other HTTP tests so all of them share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class TraceIdHttpTest {

  @Autowired private MockMvc mockMvc;

  /**
   * A real organisation, onboarded before each test. Since ADR-020 an invented identifier is
   * refused with a 404 before any handler runs, which would turn every expected status here into
   * the same one and prove nothing about the header.
   */
  private String organisation;

  @BeforeEach
  void onboardAnOrganisation() throws Exception {
    String body =
        mockMvc
            .perform(
                TestTenants.with(post("/api/organisations"), TestTenants.operatorToken(mockMvc))
                    .content("{\"name\":\"Organisation %s\"}".formatted(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    organisation = JsonPath.read(body, "$.id");
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role) {
    return request
        .header("X-Organisation", organisation)
        .header("X-User", UUID.randomUUID().toString())
        .header("X-Role", role)
        .contentType(MediaType.APPLICATION_JSON);
  }

  private String traceIdOf(MockHttpServletRequestBuilder request, int expectedStatus)
      throws Exception {
    return mockMvc
        .perform(request)
        .andExpect(status().is(expectedStatus))
        .andReturn()
        .getResponse()
        .getHeader(TraceIdResponseFilter.TRACE_ID_HEADER);
  }

  @Test
  void GivenASuccessfulRequest_WhenItIsAnswered_ThenTheResponseCarriesATraceId() throws Exception {
    assertThat(traceIdOf(as(get("/api/qualifications"), "ADMIN"), 200)).isNotBlank();
  }

  @Test
  void GivenARefusedRequest_WhenItIsAnswered_ThenTheResponseStillCarriesATraceId()
      throws Exception {
    assertThat(
            traceIdOf(
                as(post("/api/qualifications"), "SCHEDULER").content("{\"name\":\"Forklift\"}"),
                403))
        .isNotBlank();
  }

  @Test
  void GivenAMalformedRequest_WhenItIsRejectedAtTheBoundary_ThenTheResponseCarriesATraceId()
      throws Exception {
    assertThat(traceIdOf(as(get("/api/qualifications"), "SUPERVISOR"), 400)).isNotBlank();
  }

  @Test
  void GivenTwoRequests_WhenEachIsTraced_ThenTheyDoNotShareATraceId() throws Exception {
    String first = traceIdOf(as(get("/api/qualifications"), "ADMIN"), 200);
    String second = traceIdOf(as(get("/api/qualifications"), "ADMIN"), 200);

    assertThat(first).isNotEqualTo(second);
  }
}
