package io.github.naimelijah.hrsched.configuration.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Specifies the one piece of the trace that a caller can actually see, and the log lines it joins.
 *
 * <p>A refusal carrying the identifier is the case that matters: the person who can report a
 * problem is the person who was refused, and without this they have nothing to quote. What they
 * quote is only worth something if the lines the request wrote carry it too, so two lines are read
 * back off stdout, where the console writes them: the security chain's refusal, written before any
 * controller, and a use case's audit record, written inside one. Each must be JSON and carry the
 * request's trace id (ADR-016).
 *
 * <p>Annotated identically to the other HTTP tests so all of them share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
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

  /** Where the ECS console format puts the trace id Micrometer Tracing keeps in the MDC. */
  private static final String TRACE_ID_FIELD = "traceId";

  /** What {@code ProblemAccessDeniedHandler} writes when the chain refuses a signed-in caller. */
  private static final String REFUSAL = "Refused: an authenticated caller may not do this";

  /** What {@code WorkforceAuditLog} writes when a workforce change is recorded. */
  private static final String WORKFORCE_CHANGE = "Workforce change recorded";

  private static final JsonMapper JSON = JsonMapper.builder().build();

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
   * and tabs and no comma after it. Sent without a token, because that is the exposure the threat
   * model records: the header is parsed before the security chain, for anybody. What this pins is
   * that such a header is extracted ahead of the refusal and loses neither the request nor its
   * inbound trace. How long parsing it takes is the dependency audit's to catch, not a wall-clock
   * assertion's.
   */
  @Test
  void GivenAnAnonymousTracestateOfLongWhitespaceRuns_WhenRefused_ThenItKeepsTheInboundTrace()
      throws Exception {
    MockHttpServletRequestBuilder request =
        get("/api/qualifications")
            .header("traceparent", INVENTED_TRACEPARENT)
            .header("tracestate", "invented=value" + " \t".repeat(WHITESPACE_PAIRS));

    assertThat(traceIdOf(request, 401)).isEqualTo(INVENTED_TRACE_ID);
  }

  /** The chain's own refusal: account administration is an Admin's, so the operator is refused. */
  @Test
  void
      GivenARefusedRequestWithAnInboundTrace_WhenTheRefusalIsLogged_ThenTheJsonLineCarriesItsTraceId(
          CapturedOutput output) throws Exception {
    MockHttpServletRequestBuilder request =
        as(get("/api/accounts"), "OPERATOR").header("traceparent", INVENTED_TRACEPARENT);
    mockMvc.perform(request).andExpect(status().isForbidden());

    JsonNode line = theOneLineSaying(output, REFUSAL);

    assertThat(line.path(TRACE_ID_FIELD).asString())
        .as("the trace id of %s", line)
        .isEqualTo(INVENTED_TRACE_ID);
  }

  @Test
  void GivenAnAuditedChangeWithAnInboundTrace_WhenItIsRecorded_ThenTheJsonLineCarriesItsTraceId(
      CapturedOutput output) throws Exception {
    MockHttpServletRequestBuilder request =
        as(post("/api/qualifications"), "ADMIN")
            .header("traceparent", INVENTED_TRACEPARENT)
            .content("{\"name\":\"Forklift\"}");
    mockMvc.perform(request).andExpect(status().isCreated());

    JsonNode line = theOneLineSaying(output, WORKFORCE_CHANGE);

    assertThat(line.path(TRACE_ID_FIELD).asString())
        .as("the trace id of %s", line)
        .isEqualTo(INVENTED_TRACE_ID);
  }

  /**
   * Fails unless exactly one captured line says the message, and that line parses as JSON: a line a
   * collector cannot parse is a line nobody can search by trace id.
   */
  private static JsonNode theOneLineSaying(CapturedOutput output, String message) {
    List<String> lines = output.getOut().lines().filter(line -> line.contains(message)).toList();
    assertThat(lines).as("lines on stdout saying \"%s\"", message).hasSize(1);
    String line = lines.getFirst();
    JsonNode parsed;
    try {
      parsed = JSON.readTree(line);
    } catch (JacksonException notJson) {
      throw new AssertionError("A log line is not JSON: " + line, notJson);
    }
    assertThat(parsed.path("message").asString()).as("the message of %s", line).isEqualTo(message);
    return parsed;
  }
}
