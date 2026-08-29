package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Drives the whole system over HTTP: define qualifications and people, state their unavailability,
 * configure the rules, generate a roster, and publish it.
 *
 * <p>Crosses both contexts through their real adapters, so it also exercises the anti-corruption
 * layer between scheduling and workforce.
 *
 * <p>The constraint values used here are invented. They are chosen to make a specific case pass or
 * fail, and say nothing about any jurisdiction.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RosteringHttpTest {

  private static final String ACME = UUID.randomUUID().toString();
  private static final String RIVAL = UUID.randomUUID().toString();

  @Autowired private MockMvc mockMvc;

  private MockHttpServletRequestBuilder as(
      MockHttpServletRequestBuilder request, String organisation, String role) {
    return request
        .header("X-Organisation", organisation)
        .header("X-User", UUID.randomUUID().toString())
        .header("X-Role", role)
        .contentType(MediaType.APPLICATION_JSON);
  }

  private String addEmployee(String organisation, String name) throws Exception {
    String body =
        mockMvc
            .perform(
                as(post("/api/employees"), organisation, "ADMIN")
                    .content("{\"name\":\"%s\"}".formatted(name)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
  }

  private String defineShift(String organisation, String start, String end, int headcount)
      throws Exception {
    String body =
        mockMvc
            .perform(
                as(post("/api/shifts"), organisation, "SCHEDULER")
                    .content(
                        "{\"start\":\"%s\",\"end\":\"%s\",\"requiredHeadcount\":%d}"
                            .formatted(start, end, headcount)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
  }

  private String generateRoster(String organisation, String start, String end) throws Exception {
    String body =
        mockMvc
            .perform(
                as(post("/api/rosters"), organisation, "SCHEDULER")
                    .content("{\"start\":\"%s\",\"end\":\"%s\"}".formatted(start, end)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
  }

  @Test
  void GivenAvailableStaffAndNoRules_WhenGeneratingARoster_ThenTheShiftIsCovered()
      throws Exception {
    String organisation = UUID.randomUUID().toString();
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1);

    String roster = generateRoster(organisation, "2026-10-05T00:00:00Z", "2026-10-06T00:00:00Z");

    mockMvc
        .perform(as(get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.assignments.length()").value(1));
  }

  @Test
  void GivenACompliantDraft_WhenPublished_ThenItRecordsTheConstraintVersion() throws Exception {
    String organisation = UUID.randomUUID().toString();
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-06T08:00:00Z", "2026-10-06T16:00:00Z", 1);
    mockMvc
        .perform(
            as(put("/api/constraints"), organisation, "ADMIN")
                .content("{\"maximumDailyMinutes\":600}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));

    String roster = generateRoster(organisation, "2026-10-06T00:00:00Z", "2026-10-07T00:00:00Z");

    mockMvc
        .perform(as(post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.validatedAgainstConstraintVersion").value(2));
  }

  @Test
  void GivenUnavailableStaff_WhenGeneratingARoster_ThenTheShiftIsLeftUncovered() throws Exception {
    String organisation = UUID.randomUUID().toString();
    String ada = addEmployee(organisation, "Ada");
    mockMvc
        .perform(
            as(post("/api/employees/" + ada + "/unavailability"), organisation, "ADMIN")
                .content("{\"start\":\"2026-10-07T00:00:00Z\",\"end\":\"2026-10-08T00:00:00Z\"}"))
        .andExpect(status().isNoContent());
    defineShift(organisation, "2026-10-07T08:00:00Z", "2026-10-07T16:00:00Z", 1);

    String roster = generateRoster(organisation, "2026-10-07T00:00:00Z", "2026-10-08T00:00:00Z");

    mockMvc
        .perform(as(get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignments.length()").value(0));
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenGeneratingARoster_ThenTheyAreNeverUsed()
      throws Exception {
    addEmployee(RIVAL, "Their Employee");
    defineShift(ACME, "2026-10-08T08:00:00Z", "2026-10-08T16:00:00Z", 1);

    String roster = generateRoster(ACME, "2026-10-08T00:00:00Z", "2026-10-09T00:00:00Z");

    mockMvc
        .perform(as(get("/api/rosters/" + roster), ACME, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignments.length()").value(0));
  }

  @Test
  void GivenARosterOfAnotherOrganisation_WhenReadingIt_ThenItIsNotFound() throws Exception {
    String organisation = UUID.randomUUID().toString();
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-09T08:00:00Z", "2026-10-09T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-10-09T00:00:00Z", "2026-10-10T00:00:00Z");

    mockMvc
        .perform(as(get("/api/rosters/" + roster), RIVAL, "SCHEDULER"))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenAnEmployeeRole_WhenDefiningAShift_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(post("/api/shifts"), ACME, "EMPLOYEE")
                .content(
                    "{\"start\":\"2026-10-10T08:00:00Z\",\"end\":\"2026-10-10T16:00:00Z\","
                        + "\"requiredHeadcount\":1}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnInvertedPeriod_WhenDefiningAShift_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(
            as(post("/api/shifts"), ACME, "SCHEDULER")
                .content(
                    "{\"start\":\"2026-10-11T16:00:00Z\",\"end\":\"2026-10-11T08:00:00Z\","
                        + "\"requiredHeadcount\":1}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenNoConstraintsConfigured_WhenReadingThem_ThenAnEmptyFirstVersionIsReturned()
      throws Exception {
    String organisation = UUID.randomUUID().toString();

    mockMvc
        .perform(as(get("/api/constraints"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.constraints.length()").value(0));
  }

  @Test
  void GivenAConstraintThatCannotBeSatisfied_WhenGenerating_ThenNothingIsAssigned()
      throws Exception {
    String organisation = UUID.randomUUID().toString();
    addEmployee(organisation, "Ada");
    mockMvc
        .perform(
            as(put("/api/constraints"), organisation, "ADMIN")
                .content("{\"maximumDailyMinutes\":60}"))
        .andExpect(status().isOk());
    defineShift(organisation, "2026-10-12T08:00:00Z", "2026-10-12T16:00:00Z", 1);

    String roster = generateRoster(organisation, "2026-10-12T00:00:00Z", "2026-10-13T00:00:00Z");

    mockMvc
        .perform(as(get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        // An eight-hour shift cannot be filled under a one-hour daily cap.
        .andExpect(jsonPath("$.assignments.length()").value(0));
  }

  @Test
  void GivenAnEmployeeAndAScheduler_WhenAnEmployeeConfiguresConstraints_ThenItIsForbidden()
      throws Exception {
    mockMvc
        .perform(
            as(put("/api/constraints"), ACME, "SCHEDULER").content("{\"maximumDailyMinutes\":600}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAPublishedRoster_WhenPublishedAgain_ThenItIsRejected() throws Exception {
    String organisation = UUID.randomUUID().toString();
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-13T08:00:00Z", "2026-10-13T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-10-13T00:00:00Z", "2026-10-14T00:00:00Z");
    mockMvc
        .perform(as(post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isOk());

    mockMvc
        .perform(as(post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenTwoOrganisations_WhenEachConfiguresRules_ThenNeitherSeesTheOther() throws Exception {
    String first = UUID.randomUUID().toString();
    String second = UUID.randomUUID().toString();
    mockMvc
        .perform(
            as(put("/api/constraints"), first, "ADMIN").content("{\"maximumConsecutiveDays\":3}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(as(get("/api/constraints"), second, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.constraints.length()").value(0));

    assertThat(first).isNotEqualTo(second);
  }
}
