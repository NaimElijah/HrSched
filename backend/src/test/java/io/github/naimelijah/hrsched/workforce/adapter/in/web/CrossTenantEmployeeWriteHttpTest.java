package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.as;
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
 * An Admin of one organisation naming another organisation's employee, on each route that writes to
 * an employee's record. Each is answered 404, and the other organisation's record is read back
 * unchanged by that organisation's own Admin.
 *
 * <p>These are the tests whose failure is a security incident rather than a bug. The organisation
 * comes from the token, so the only thing between the caller and the other tenant's row is that the
 * employee is looked up within the caller's organisation. One test per route rather than a table:
 * each is a tenancy decision, and a table would hide which row's failure is the incident.
 *
 * <p>EVERY INSTANT AND FIGURE BELOW IS INVENTED.
 *
 * <p>Annotated identically to {@code EmployeeHttpTest}, so it shares the suite's Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class CrossTenantEmployeeWriteHttpTest {

  /** Invented: a term of an individual agreement, never a statutory figure. */
  private static final long THEIR_CONTRACTED_MINUTES = 1800;

  /** Invented: what the other organisation's Admin tries to set it to. */
  private static final long OUR_CONTRACTED_MINUTES = 600;

  /** Invented: the span the other organisation's employee already declared or stated. */
  private static final String THEIR_SPAN =
      "{\"start\":\"2026-11-18T08:00:00Z\",\"end\":\"2026-11-18T16:00:00Z\"}";

  /** Invented: the span the other organisation's Admin tries to add, overlapping nothing. */
  private static final String OUR_SPAN =
      "{\"start\":\"2026-11-19T08:00:00Z\",\"end\":\"2026-11-19T16:00:00Z\"}";

  /** Invented: a preference stated about {@link #THEIR_SPAN}. */
  private static final String THEIR_PREFERENCE =
      "{\"start\":\"2026-11-18T08:00:00Z\",\"end\":\"2026-11-18T16:00:00Z\","
          + "\"strength\":\"PREFERS\"}";

  /** Invented: a preference about {@link #OUR_SPAN}. */
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

  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenSettingOurEmployeesContract_ThenNotFoundAndUnchanged()
      throws Exception {
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
  void GivenAnAdminOfAnotherOrganisation_WhenStatingOurEmployeesPreference_ThenNotFoundUnchanged()
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
        .andExpect(jsonPath("$[0].start").value("2026-11-18T08:00:00Z"))
        .andExpect(jsonPath("$[0].strength").value("PREFERS"));
  }

  @Test
  void GivenAnAdminOfAnotherOrganisation_WhenDeclaringOurEmployeesAbsence_ThenNotFoundUnchanged()
      throws Exception {
    String theirs = anOrganisation(mockMvc);
    String theirEmployee = addEmployee(theirs);
    mockMvc
        .perform(
            as(
                    mockMvc,
                    post("/api/employees/" + theirEmployee + "/unavailability"),
                    theirs,
                    "ADMIN")
                .content(THEIR_SPAN))
        .andExpect(status().isNoContent());
    String ours = anOrganisation(mockMvc);

    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + theirEmployee + "/unavailability"), ours, "ADMIN")
                .content(OUR_SPAN))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(
            as(
                mockMvc,
                get("/api/employees/" + theirEmployee + "/unavailability"),
                theirs,
                "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].start").value("2026-11-18T08:00:00Z"));
  }
}
