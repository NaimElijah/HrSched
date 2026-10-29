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

  /** Invented: a W3C trace id, 32 lowercase hex digits, that no real trace carries. */
  private static final String INVENTED_TRACE_ID = "5e1ec7ed5e1ec7ed5e1ec7ed5e1ec7ed";

  /** Invented: a {@code traceparent} carrying {@link #INVENTED_TRACE_ID} and an invented span. */
  private static final String INVENTED_TRACEPARENT =
      "00-" + INVENTED_TRACE_ID + "-c0ffee00c0ffee00-01";

  /**
   * Invented: enough space-and-tab pairs to have the advisory's shape, few enough to parse quickly
   * on a vulnerable version too, so the test asserts behaviour and never time.
   */
  private static final int WHITESPACE_PAIRS = 512;

  @Autowired private MockMvc mockMvc;

  /**
   * A real organisation, onboarded before each test. Every request here signs in as an account in
   * it, and an account can only belong to an organisation that exists, so an invented identifier
   * would fail before any expected status here could be observed.
   */
  private String organisation;

  @BeforeEach
  void onboardAnOrganisation() throws Exception {
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
    organisation = JsonPath.read(body, "$.id");
  }

  private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String role)
      throws Exception {
    return TestTenants.as(mockMvc, request, organisation, role);
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
    assertThat(traceIdOf(as(get("/api/rosters/not-a-uuid"), "SCHEDULER"), 400)).isNotBlank();
  }

  @Test
  void GivenTwoRequests_WhenEachIsTraced_ThenTheyDoNotShareATraceId() throws Exception {
    String first = traceIdOf(as(get("/api/qualifications"), "ADMIN"), 200);
    String second = traceIdOf(as(get("/api/qualifications"), "ADMIN"), 200);

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void GivenAnInboundTraceparent_WhenTheRequestIsAnswered_ThenTheResponseCarriesItsTraceId()
      throws Exception {
    MockHttpServletRequestBuilder request =
        as(get("/api/qualifications"), "ADMIN")
            .header("traceparent", INVENTED_TRACEPARENT)
            .header("tracestate", "invented=value");

    assertThat(traceIdOf(request, 200)).isEqualTo(INVENTED_TRACE_ID);
  }

  /**
   * The input class of GHSA-ccrj-gw8r-854m (#285): a {@code tracestate} with a long run of spaces
   * and tabs and no comma after it. What this pins is that such a header neither fails the request
   * nor loses the inbound trace. How long parsing it takes is the dependency audit's to catch, not
   * a wall-clock assertion's.
   */
  @Test
  void GivenATracestateOfLongWhitespaceRuns_WhenTheRequestIsAnswered_ThenItKeepsTheInboundTrace()
      throws Exception {
    MockHttpServletRequestBuilder request =
        as(get("/api/qualifications"), "ADMIN")
            .header("traceparent", INVENTED_TRACEPARENT)
            .header("tracestate", "invented=value" + " \t".repeat(WHITESPACE_PAIRS));

    assertThat(traceIdOf(request, 200)).isEqualTo(INVENTED_TRACE_ID);
  }
}
