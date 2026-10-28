package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.as;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Our Admin naming their employee — an employee of another organisation — on the routes that write
 * to an employee's record and that {@code EmployeeHttpTest} does not already pin: setting a
 * contract, stating a preference, declaring unavailability, and withdrawing unavailability.
 * Granting a qualification and withdrawing a preference across tenants are pinned there. Each here
 * is answered 404, and their record is read back unchanged by their own Admin.
 *
 * <p>Read from one side throughout: "our" is the organisation of whoever sends the request, "their"
 * is the other one, whose record it names.
 *
 * <p>These are the tests whose failure is a security incident rather than a bug. The organisation
 * comes from the token, so the only thing between us and their row is that the employee is looked
 * up within our organisation. One test per route rather than a table: each is a tenancy decision,
 * and a table would hide which row's failure is the incident.
 *
 * <p>EVERY INSTANT AND FIGURE BELOW IS INVENTED.
 *
 * <p>Annotated identically to {@code EmployeeHttpTest}, so it shares the suite's Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class CrossTenantEmployeeWriteHttpTest {

  /** Invented: their employee's contract, a term of an individual agreement, never a statute's. */
  private static final long THEIR_CONTRACTED_MINUTES = 1800;

  /** Invented: what our Admin tries to set it to. */
  private static final long OUR_CONTRACTED_MINUTES = 600;

  /** Invented: when their employee's span begins, as declared or stated by their own Admin. */
  private static final String THEIR_START = "2026-11-18T08:00:00Z";

  /** Invented: when it ends. */
  private static final String THEIR_END = "2026-11-18T16:00:00Z";

  /** Invented: the span their employee already declared or stated. */
  private static final String THEIR_SPAN =
      "{\"start\":\"%s\",\"end\":\"%s\"}".formatted(THEIR_START, THEIR_END);

  /** Invented: the span our Admin tries to add, overlapping nothing. */
  private static final String OUR_SPAN =
      "{\"start\":\"2026-11-19T08:00:00Z\",\"end\":\"2026-11-19T16:00:00Z\"}";

  /** Invented: a preference their Admin stated about {@link #THEIR_SPAN}. */
  private static final String THEIR_PREFERENCE =
      "{\"start\":\"%s\",\"end\":\"%s\",\"strength\":\"PREFERS\"}"
          .formatted(THEIR_START, THEIR_END);

  /** Invented: the preference our Admin tries to state, about {@link #OUR_SPAN}. */
  private static final String OUR_PREFERENCE =
      "{\"start\":\"2026-11-19T08:00:00Z\",\"end\":\"2026-11-19T16:00:00Z\","
          + "\"strength\":\"PREFERS_NOT\"}";

  @Autowired private MockMvc mockMvc;

  private String addEmployee(String organisation) throws Exception {
    String body =
        mockMvc
            .perform(
                as(mockMvc, post("/api/employees"), organisation, "ADMIN")
                    .content("{\"name\":\"Their Employee\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  private void declareTheirUnavailability(String theirs, String theirEmployee) throws Exception {
    mockMvc
        .perform(
            as(
                    mockMvc,
                    post("/api/employees/" + theirEmployee + "/unavailability"),
                    theirs,
                    "ADMIN")
                .content(THEIR_SPAN))
        .andExpect(status().isNoContent());
  }

  private void theirUnavailabilityIsStillOnlyTheirSpan(String theirs, String theirEmployee)
      throws Exception {
    mockMvc
        .perform(
            as(
                mockMvc,
                get("/api/employees/" + theirEmployee + "/unavailability"),
                theirs,
                "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].start").value(THEIR_START))
        .andExpect(jsonPath("$[0].end").value(THEIR_END));
  }

  @Test
  void GivenOurAdmin_WhenSettingTheirEmployeesContract_ThenNotFoundAndUnchanged() throws Exception {
    String theirs = anOrganisation(mockMvc);
    String theirEmployee = addEmployee(theirs);
    mockMvc
        .perform(
            as(mockMvc, put("/api/employees/" + theirEmployee + "/contract"), theirs, "ADMIN")
                .content("{\"weeklyContractedMinutes\":%d}".formatted(THEIR_CONTRACTED_MINUTES)))
        .andExpect(status().isNoContent());
    String ours = anOrganisation(mockMvc);

    mockMvc
        .perform(
            as(mockMvc, put("/api/employees/" + theirEmployee + "/contract"), ours, "ADMIN")
                .content("{\"weeklyContractedMinutes\":%d}".formatted(OUR_CONTRACTED_MINUTES)))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(as(mockMvc, get("/api/employees/" + theirEmployee), theirs, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.weeklyContractedMinutes").value(THEIR_CONTRACTED_MINUTES));
  }

  @Test
  void GivenOurAdmin_WhenStatingTheirEmployeesPreference_ThenNotFoundAndUnchanged()
      throws Exception {
    String theirs = anOrganisation(mockMvc);
    String theirEmployee = addEmployee(theirs);
    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + theirEmployee + "/preferences"), theirs, "ADMIN")
                .content(THEIR_PREFERENCE))
        .andExpect(status().isNoContent());
    String ours = anOrganisation(mockMvc);

    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + theirEmployee + "/preferences"), ours, "ADMIN")
                .content(OUR_PREFERENCE))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(
            as(mockMvc, get("/api/employees/" + theirEmployee + "/preferences"), theirs, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].start").value(THEIR_START))
        .andExpect(jsonPath("$[0].strength").value("PREFERS"));
  }

  @Test
  void GivenOurAdmin_WhenDeclaringTheirEmployeesAbsence_ThenNotFoundAndUnchanged()
      throws Exception {
    String theirs = anOrganisation(mockMvc);
    String theirEmployee = addEmployee(theirs);
    declareTheirUnavailability(theirs, theirEmployee);
    String ours = anOrganisation(mockMvc);

    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + theirEmployee + "/unavailability"), ours, "ADMIN")
                .content(OUR_SPAN))
        .andExpect(status().isNotFound());

    theirUnavailabilityIsStillOnlyTheirSpan(theirs, theirEmployee);
  }

  /**
   * Named by exactly the span their Admin declared, so the only thing standing between us and
   * withdrawing it is that their employee is not looked up within our organisation.
   */
  @Test
  void GivenOurAdmin_WhenWithdrawingTheirEmployeesAbsence_ThenNotFoundAndStillListed()
      throws Exception {
    String theirs = anOrganisation(mockMvc);
    String theirEmployee = addEmployee(theirs);
    declareTheirUnavailability(theirs, theirEmployee);
    String ours = anOrganisation(mockMvc);

    mockMvc
        .perform(
            as(
                mockMvc,
                delete("/api/employees/" + theirEmployee + "/unavailability")
                    .param("start", THEIR_START)
                    .param("end", THEIR_END),
                ours,
                "ADMIN"))
        .andExpect(status().isNotFound());

    theirUnavailabilityIsStillOnlyTheirSpan(theirs, theirEmployee);
  }
}
