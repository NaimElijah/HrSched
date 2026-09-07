package io.github.naimelijah.hrsched.configuration.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.configuration.observability.TraceIdResponseFilter;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
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
 * Specification of the boundary check that decides whether a claimed tenant is a real one.
 *
 * <p>The requirement this exists for is the one the documents call a security incident rather than
 * a bug: before it, {@code X-Organisation} was believed on sight, so any caller could invent a
 * tenant with {@code uuidgen} and read and write freely under it. The test that matters most here
 * is therefore the one asserting that data cannot be created under an organisation nobody
 * onboarded.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class KnownOrganisationHttpTest {

  @Autowired private MockMvc mockMvc;

  private String anOrganisation() throws Exception {
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

  private static MockHttpServletRequestBuilder as(
      MockHttpServletRequestBuilder request, String organisation, String role) {
    return request
        .header("X-Organisation", organisation)
        .header("X-User", UUID.randomUUID().toString())
        .header("X-Role", role)
        .contentType(MediaType.APPLICATION_JSON);
  }

  @Test
  void GivenAnOrganisationNobodyOnboarded_WhenReadingThroughIt_ThenThereIsNothingThere()
      throws Exception {
    mockMvc
        .perform(as(get("/api/qualifications"), UUID.randomUUID().toString(), "ADMIN"))
        .andExpect(status().isNotFound());
  }

  /**
   * The property the whole change exists for. Inventing a tenant used to be a way to get a working,
   * private, entirely unaccountable corner of the system.
   */
  @Test
  void GivenAnOrganisationNobodyOnboarded_WhenCreatingDataUnderIt_ThenItIsRefused()
      throws Exception {
    String invented = UUID.randomUUID().toString();

    mockMvc
        .perform(as(post("/api/employees"), invented, "ADMIN").content("{\"name\":\"Ada\"}"))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(
            as(post("/api/qualifications"), invented, "ADMIN").content("{\"name\":\"Forklift\"}"))
        .andExpect(status().isNotFound());
  }

  /**
   * The refusal is shaped like every other 404 and echoes nothing the caller sent, so it adds no
   * signal beyond the status. It cannot claim more than that: a known organisation answers
   * differently, which is the oracle ADR-020 records rather than defends.
   */
  @Test
  void GivenAnUnknownOrganisation_WhenRefused_ThenTheBodySaysNothingAboutWhatWasClaimed()
      throws Exception {
    String invented = UUID.randomUUID().toString();

    String body =
        mockMvc
            .perform(as(get("/api/qualifications"), invented, "ADMIN"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.title").value("Not found"))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.detail").value("No such resource."))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body)
        .describedAs("a refusal that quotes the identifier confirms it was understood")
        .doesNotContain(invented);
  }

  @Test
  void GivenAnOnboardedOrganisation_WhenReadingThroughIt_ThenTheRequestIsServed() throws Exception {
    mockMvc
        .perform(as(get("/api/qualifications"), anOrganisation(), "ADMIN"))
        .andExpect(status().isOk());
  }

  /** Without this exemption the first organisation could never be created. */
  @Test
  void GivenNoOrganisationExistsYet_WhenOnboardingOne_ThenItIsNotRefusedByTheBoundary()
      throws Exception {
    mockMvc
        .perform(
            post("/api/organisations")
                .header("X-User", UUID.randomUUID().toString())
                .header("X-Role", "OPERATOR")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Acme\"}"))
        .andExpect(status().isCreated());
  }

  /**
   * A plainly broken request deserves a plainly broken answer. Answering 404 to an unparseable
   * header would report a malformed request as a missing one.
   */
  @Test
  void GivenAnOrganisationHeaderThatIsNotAUuid_WhenSent_ThenItIsABadRequestNotAMissingOne()
      throws Exception {
    mockMvc
        .perform(as(get("/api/qualifications"), "not-a-uuid", "ADMIN"))
        .andExpect(status().isBadRequest());
  }

  /** Whether an operation needs the header is the controller's business, not this filter's. */
  @Test
  void GivenNoOrganisationHeaderAtAll_WhenSent_ThenTheControllerStillAnswersForIt()
      throws Exception {
    mockMvc
        .perform(
            get("/api/qualifications")
                .header("X-User", UUID.randomUUID().toString())
                .header("X-Role", "ADMIN"))
        .andExpect(status().isBadRequest());
  }

  /** Actuator belongs to no tenant, and a stray header must not be able to shut it. */
  @Test
  void GivenAStrayOrganisationHeader_WhenAskingForHealth_ThenActuatorIsUnaffected()
      throws Exception {
    mockMvc
        .perform(get("/actuator/health").header("X-Organisation", UUID.randomUUID().toString()))
        .andExpect(status().isOk());
  }

  /**
   * The reason this filter is ordered after {@link TraceIdResponseFilter}. A refusal nobody can
   * quote is a refusal nobody can report.
   */
  @Test
  void GivenARefusalWrittenByTheFilter_WhenItIsAnswered_ThenItStillCarriesATraceId()
      throws Exception {
    String traceId =
        mockMvc
            .perform(as(get("/api/qualifications"), UUID.randomUUID().toString(), "ADMIN"))
            .andExpect(status().isNotFound())
            .andReturn()
            .getResponse()
            .getHeader(TraceIdResponseFilter.TRACE_ID_HEADER);

    assertThat(traceId).isNotBlank();
  }
}
