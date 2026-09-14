package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class RosteringHttpTest {

  @Autowired private MockMvc mockMvc;

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

  private String defineQualification(String organisation, String name) throws Exception {
    String body =
        mockMvc
            .perform(
                as(post("/api/qualifications"), organisation, "ADMIN")
                    .content("{\"name\":\"%s\"}".formatted(name)))
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
  void GivenPublishedWork_WhenAnEmployeeReadsTheirOwnSchedule_ThenTheySeeIt() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String shift = defineShift(organisation, "2026-11-09T08:00:00Z", "2026-11-09T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-11-09T00:00:00Z", "2026-11-10T00:00:00Z");
    mockMvc
        .perform(as(post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            as(get("/api/me/schedule"), organisation, "EMPLOYEE")
                .header("X-Employee", ada)
                .param("from", "2026-11-09T00:00:00Z")
                .param("to", "2026-11-10T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].shift").value(shift))
        .andExpect(jsonPath("$[0].roster").value(roster));
  }

  /** A draft binds nobody, so it is not somebody's schedule. */
  @Test
  void GivenTheirWorkIsOnlyADraft_WhenReadingTheirOwnSchedule_ThenTheyAreShownNothing()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-11-10T08:00:00Z", "2026-11-10T16:00:00Z", 1);
    generateRoster(organisation, "2026-11-10T00:00:00Z", "2026-11-11T00:00:00Z");

    mockMvc
        .perform(
            as(get("/api/me/schedule"), organisation, "EMPLOYEE")
                .header("X-Employee", ada)
                .param("from", "2026-11-10T00:00:00Z")
                .param("to", "2026-11-11T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void GivenNoPersonnelRecordIsClaimed_WhenReadingTheirOwnSchedule_ThenItIsForbidden()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    mockMvc
        .perform(
            as(get("/api/me/schedule"), organisation, "EMPLOYEE")
                .param("from", "2026-11-09T00:00:00Z")
                .param("to", "2026-11-10T00:00:00Z"))
        .andExpect(status().isForbidden());
  }

  /**
   * An employee still cannot read the roster itself (R12): it names every colleague, and the narrow
   * capability beside it exists precisely so that the wide one does not have to be opened.
   */
  @Test
  void GivenAnEmployeeRole_WhenReadingTheWholeRoster_ThenItIsStillForbidden() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-11-11T08:00:00Z", "2026-11-11T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-11-11T00:00:00Z", "2026-11-12T00:00:00Z");

    mockMvc
        .perform(
            as(get("/api/rosters/" + roster), organisation, "EMPLOYEE").header("X-Employee", ada))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenADraftAndAShift_WhenASchedulerAssignsByHand_ThenTheRosterCarriesIt() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String roster = generateRoster(organisation, "2026-11-02T00:00:00Z", "2026-11-03T00:00:00Z");
    String shift = defineShift(organisation, "2026-11-02T08:00:00Z", "2026-11-02T16:00:00Z", 1);

    mockMvc
        .perform(
            as(post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(shift, ada)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignments.length()").value(1))
        .andExpect(jsonPath("$.assignments[0].employee").value(ada));

    mockMvc
        .perform(
            as(
                delete("/api/rosters/%s/assignments/%s/%s".formatted(roster, shift, ada)),
                organisation,
                "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignments.length()").value(0));
  }

  @Test
  void GivenAnAdminRole_WhenAssigningByHand_ThenItIsForbidden() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String roster = generateRoster(organisation, "2026-11-03T00:00:00Z", "2026-11-04T00:00:00Z");
    String shift = defineShift(organisation, "2026-11-03T08:00:00Z", "2026-11-03T16:00:00Z", 1);

    mockMvc
        .perform(
            as(post("/api/rosters/" + roster + "/assignments"), organisation, "ADMIN")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(shift, ada)))
        .andExpect(status().isForbidden());
  }

  /**
   * Rule R4 broken is a 422, not a 400: the caller named real things and asked for the forbidden.
   */
  @Test
  void GivenAShiftDemandingAQualificationTheyLack_WhenAssigning_ThenItIsUnprocessable()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String forklift = defineQualification(organisation, "Forklift");
    String roster = generateRoster(organisation, "2026-11-04T00:00:00Z", "2026-11-05T00:00:00Z");
    String shift =
        mockMvc
            .perform(
                as(post("/api/shifts"), organisation, "SCHEDULER")
                    .content(
                        ("{\"start\":\"2026-11-04T08:00:00Z\",\"end\":\"2026-11-04T16:00:00Z\","
                                + "\"requiredHeadcount\":1,\"requiredQualifications\":[\"%s\"]}")
                            .formatted(forklift)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString()
            .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

    mockMvc
        .perform(
            as(post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(shift, ada)))
        .andExpect(status().isUnprocessableEntity());
  }

  /**
   * Rule R3 on the manual path, with no rules configured: a 422 problem the board shows with its
   * reason, as it shows the other assignment refusals.
   */
  @Test
  void GivenSomebodyAlreadyOnAnOverlappingShift_WhenAssigning_ThenItIsUnprocessable()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String roster = generateRoster(organisation, "2026-11-16T00:00:00Z", "2026-11-17T00:00:00Z");
    String day = defineShift(organisation, "2026-11-16T08:00:00Z", "2026-11-16T16:00:00Z", 1);
    String afternoon = defineShift(organisation, "2026-11-16T12:00:00Z", "2026-11-16T20:00:00Z", 1);
    mockMvc
        .perform(
            as(post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(day, ada)))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            as(post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(afternoon, ada)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Assignment refused"))
        .andExpect(jsonPath("$.detail").value(containsString("overlap")));
  }

  @Test
  void GivenSomebodyNotInTheOrganisation_WhenAssigningThem_ThenItIsNotFound() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String stranger = addEmployee(anOrganisation(mockMvc), "Their Employee");
    String roster = generateRoster(organisation, "2026-11-05T00:00:00Z", "2026-11-06T00:00:00Z");
    String shift = defineShift(organisation, "2026-11-05T08:00:00Z", "2026-11-05T16:00:00Z", 1);

    mockMvc
        .perform(
            as(post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(shift, stranger)))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenNoEmployeeInTheBody_WhenAssigning_ThenItIsABadRequest() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String roster = generateRoster(organisation, "2026-11-06T00:00:00Z", "2026-11-07T00:00:00Z");
    String shift = defineShift(organisation, "2026-11-06T08:00:00Z", "2026-11-06T16:00:00Z", 1);

    mockMvc
        .perform(
            as(post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
                .content("{\"shift\":\"%s\"}".formatted(shift)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAvailableStaffAndNoRules_WhenGeneratingARoster_ThenTheShiftIsCovered()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
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
    String organisation = anOrganisation(mockMvc);
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
    String organisation = anOrganisation(mockMvc);
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
    String ours = anOrganisation(mockMvc);
    addEmployee(anOrganisation(mockMvc), "Their Employee");
    defineShift(ours, "2026-10-08T08:00:00Z", "2026-10-08T16:00:00Z", 1);

    String roster = generateRoster(ours, "2026-10-08T00:00:00Z", "2026-10-09T00:00:00Z");

    mockMvc
        .perform(as(get("/api/rosters/" + roster), ours, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignments.length()").value(0));
  }

  @Test
  void GivenARosterOfAnotherOrganisation_WhenReadingIt_ThenItIsNotFound() throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-09T08:00:00Z", "2026-10-09T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-10-09T00:00:00Z", "2026-10-10T00:00:00Z");

    mockMvc
        .perform(as(get("/api/rosters/" + roster), anOrganisation(mockMvc), "SCHEDULER"))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenAnEmployeeRole_WhenDefiningAShift_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(post("/api/shifts"), anOrganisation(mockMvc), "EMPLOYEE")
                .content(
                    "{\"start\":\"2026-10-10T08:00:00Z\",\"end\":\"2026-10-10T16:00:00Z\","
                        + "\"requiredHeadcount\":1}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnInvertedPeriod_WhenDefiningAShift_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(
            as(post("/api/shifts"), anOrganisation(mockMvc), "SCHEDULER")
                .content(
                    "{\"start\":\"2026-10-11T16:00:00Z\",\"end\":\"2026-10-11T08:00:00Z\","
                        + "\"requiredHeadcount\":1}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenNoConstraintsConfigured_WhenReadingThem_ThenAnEmptyFirstVersionIsReturned()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    mockMvc
        .perform(as(get("/api/constraints"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.constraints.length()").value(0));
  }

  @Test
  void GivenAConstraintThatCannotBeSatisfied_WhenGenerating_ThenNothingIsAssigned()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
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
  void GivenAScheduler_WhenConfiguringConstraints_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(put("/api/constraints"), anOrganisation(mockMvc), "SCHEDULER")
                .content("{\"maximumDailyMinutes\":600}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnEmployee_WhenConfiguringConstraints_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(put("/api/constraints"), anOrganisation(mockMvc), "EMPLOYEE")
                .content("{\"maximumDailyMinutes\":600}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAPublishedRoster_WhenPublishedAgain_ThenItIsRejected() throws Exception {
    String organisation = anOrganisation(mockMvc);
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
    String first = anOrganisation(mockMvc);
    String second = anOrganisation(mockMvc);
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

  @Test
  void GivenRulesTightenedAfterGeneration_WhenPublishing_ThenEachViolationIsStructured()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-26T08:00:00Z", "2026-10-26T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-10-26T00:00:00Z", "2026-10-27T00:00:00Z");
    // Invented: one hour a day, which the eight-hour shift already assigned cannot satisfy.
    mockMvc
        .perform(
            as(put("/api/constraints"), organisation, "ADMIN")
                .content("{\"maximumDailyMinutes\":60}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(as(post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.violations.length()").value(1))
        // Structured, so a caller can resolve the employee to a name without parsing prose.
        .andExpect(jsonPath("$.violations[0].employee").value(ada))
        .andExpect(jsonPath("$.violations[0].constraint").value("maximum daily working time"))
        .andExpect(jsonPath("$.violations[0].detail").isString());
  }

  @Test
  void GivenDefinedShifts_WhenListingThemForASpan_ThenTheyAreReturned() throws Exception {
    String organisation = anOrganisation(mockMvc);
    defineShift(organisation, "2026-10-21T08:00:00Z", "2026-10-21T16:00:00Z", 2);

    mockMvc
        .perform(
            as(get("/api/shifts"), organisation, "SCHEDULER")
                .param("from", "2026-10-21T00:00:00Z")
                .param("to", "2026-10-22T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].requiredHeadcount").value(2));
  }

  @Test
  void GivenShiftsOfAnotherOrganisation_WhenListingThem_ThenNothingLeaks() throws Exception {
    defineShift(anOrganisation(mockMvc), "2026-10-22T08:00:00Z", "2026-10-22T16:00:00Z", 1);

    mockMvc
        .perform(
            as(get("/api/shifts"), anOrganisation(mockMvc), "SCHEDULER")
                .param("from", "2026-10-22T00:00:00Z")
                .param("to", "2026-10-23T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void GivenNoSpan_WhenListingShifts_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(as(get("/api/shifts"), anOrganisation(mockMvc), "SCHEDULER"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAGeneratedRoster_WhenListingRosters_ThenItIsReturned() throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-23T08:00:00Z", "2026-10-23T16:00:00Z", 1);
    generateRoster(organisation, "2026-10-23T00:00:00Z", "2026-10-24T00:00:00Z");

    mockMvc
        .perform(as(get("/api/rosters"), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].status").value("DRAFT"));
  }

  @Test
  void GivenRostersOfAnotherOrganisation_WhenListingThem_ThenNothingLeaks() throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-24T08:00:00Z", "2026-10-24T16:00:00Z", 1);
    generateRoster(organisation, "2026-10-24T00:00:00Z", "2026-10-25T00:00:00Z");

    mockMvc
        .perform(as(get("/api/rosters"), anOrganisation(mockMvc), "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void GivenConfiguredRules_WhenReadingThem_ThenTheirThresholdsComeBackNotJustTheirNames()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    // Invented values, chosen to be recognisable in the response. They are not any statute's.
    mockMvc
        .perform(
            as(put("/api/constraints"), organisation, "ADMIN")
                .content("{\"minimumRestMinutes\":661,\"maximumConsecutiveDays\":5}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(as(get("/api/constraints"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        // Revising replaces the whole set, so a caller changing one rule must be able to read the
        // others back or it will silently delete them.
        .andExpect(jsonPath("$.minimumRestMinutes").value(661))
        .andExpect(jsonPath("$.maximumConsecutiveDays").value(5))
        .andExpect(jsonPath("$.maximumDailyMinutes").doesNotExist());
  }

  @Test
  void GivenAnEmployee_WhenListingRosters_ThenItIsForbidden() throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-25T08:00:00Z", "2026-10-25T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-10-25T00:00:00Z", "2026-10-26T00:00:00Z");

    // A roster says who works when, which is personal data about every colleague.
    mockMvc
        .perform(as(get("/api/rosters"), organisation, "EMPLOYEE"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(as(get("/api/rosters/" + roster), organisation, "EMPLOYEE"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenARoster_WhenRead_ThenItCarriesThePeriodItCovers() throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-27T08:00:00Z", "2026-10-27T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-10-27T00:00:00Z", "2026-10-28T00:00:00Z");

    // Without the period a caller cannot resolve the roster's assignments against the right shifts.
    mockMvc
        .perform(as(get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.start").value("2026-10-27T00:00:00Z"))
        .andExpect(jsonPath("$.end").value("2026-10-28T00:00:00Z"));
  }

  @Test
  void GivenABodyWithoutAPeriod_WhenDefiningAShift_ThenItIsABadRequestNotAServerError()
      throws Exception {
    mockMvc
        .perform(
            as(post("/api/shifts"), anOrganisation(mockMvc), "SCHEDULER")
                .content("{\"requiredHeadcount\":1}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAnOrganisationHeaderThatIsNotAUuid_WhenGenerating_ThenItIsABadRequest()
      throws Exception {
    mockMvc
        .perform(
            as(post("/api/rosters"), "not-a-uuid", "SCHEDULER")
                .content("{\"start\":\"2026-10-05T00:00:00Z\",\"end\":\"2026-10-06T00:00:00Z\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenARoleNobodyDefined_WhenDefiningAShift_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(
            as(post("/api/shifts"), anOrganisation(mockMvc), "SUPREME_LEADER")
                .content(
                    "{\"start\":\"2026-10-05T08:00:00Z\",\"end\":\"2026-10-05T16:00:00Z\","
                        + "\"requiredHeadcount\":1}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAQualificationNobodyDefined_WhenDefiningAShift_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(
            as(post("/api/shifts"), anOrganisation(mockMvc), "SCHEDULER")
                .content(
                    "{\"start\":\"2026-10-05T08:00:00Z\",\"end\":\"2026-10-05T16:00:00Z\","
                        + "\"requiredHeadcount\":1,\"requiredQualifications\":[\"%s\"]}"
                            .formatted(UUID.randomUUID())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAPlatformOperator_WhenReadingAnotherOrganisationsRoster_ThenItIsPermitted()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-19T08:00:00Z", "2026-10-19T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-10-19T00:00:00Z", "2026-10-20T00:00:00Z");

    mockMvc
        .perform(as(get("/api/rosters/" + roster), organisation, "OPERATOR"))
        .andExpect(status().isOk());
  }

  @Test
  void GivenATenantActor_WhenReadingAnotherOrganisationsRoster_ThenItIsNotFound() throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-20T08:00:00Z", "2026-10-20T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z");

    mockMvc
        .perform(as(get("/api/rosters/" + roster), anOrganisation(mockMvc), "SCHEDULER"))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenADefinedShift_WhenFollowingItsLocation_ThenItIsFound() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String location =
        mockMvc
            .perform(
                as(post("/api/shifts"), organisation, "SCHEDULER")
                    .content(
                        "{\"start\":\"2026-11-16T08:00:00Z\",\"end\":\"2026-11-16T16:00:00Z\",\"requiredHeadcount\":2}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader("Location");

    // Any member, as for the listing: a shift is work that needs covering, not a person.
    mockMvc
        .perform(as(get(location), organisation, "EMPLOYEE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.start").value("2026-11-16T08:00:00Z"))
        .andExpect(jsonPath("$.requiredHeadcount").value(2));
  }

  @Test
  void GivenAShiftOfAnotherOrganisation_WhenReadThroughOurs_ThenThereIsNothingThere()
      throws Exception {
    String theirs =
        defineShift(anOrganisation(mockMvc), "2026-11-16T08:00:00Z", "2026-11-16T16:00:00Z", 1);

    mockMvc
        .perform(as(get("/api/shifts/" + theirs), anOrganisation(mockMvc), "SCHEDULER"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }
}
