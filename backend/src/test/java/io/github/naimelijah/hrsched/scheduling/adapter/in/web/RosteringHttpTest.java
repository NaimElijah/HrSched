package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.as;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.asThemself;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.generateDraft;
import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.STUCK_AFTER_SECONDS;
import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.awaitSomebodyWaitingOn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.scheduling.application.port.in.AssignToShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.PlanRostersUseCase;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
  @Autowired private PlanRostersUseCase planRosters;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private JdbcClient jdbc;

  private String addEmployee(String organisation, String name) throws Exception {
    String body =
        mockMvc
            .perform(
                as(mockMvc, post("/api/employees"), organisation, "ADMIN")
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
                as(mockMvc, post("/api/shifts"), organisation, "SCHEDULER")
                    .content(
                        "{\"start\":\"%s\",\"end\":\"%s\",\"zone\":\"UTC\",\"requiredHeadcount\":%d}"
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
                as(mockMvc, post("/api/qualifications"), organisation, "ADMIN")
                    .content("{\"name\":\"%s\"}".formatted(name)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
  }

  private String generateRoster(String organisation, String start, String end) throws Exception {
    return generateDraft(mockMvc, organisation, start, end);
  }

  @Test
  void GivenPublishedWork_WhenAnEmployeeReadsTheirOwnSchedule_ThenTheySeeIt() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String shift = defineShift(organisation, "2026-11-09T08:00:00Z", "2026-11-09T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-11-09T00:00:00Z", "2026-11-10T00:00:00Z");
    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            asThemself(mockMvc, get("/api/me/schedule"), organisation, ada)
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
            asThemself(mockMvc, get("/api/me/schedule"), organisation, ada)
                .param("from", "2026-11-10T00:00:00Z")
                .param("to", "2026-11-11T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  /**
   * A Scheduler, because an account holding the Employee role is always linked to somebody (R34),
   * and only the other roles can be nobody's.
   */
  @Test
  void GivenNoPersonnelRecordIsClaimed_WhenReadingTheirOwnSchedule_ThenItIsForbidden()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    mockMvc
        .perform(
            as(mockMvc, get("/api/me/schedule"), organisation, "SCHEDULER")
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
        .perform(asThemself(mockMvc, get("/api/rosters/" + roster), organisation, ada))
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
            as(mockMvc, post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(shift, ada)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignments.length()").value(1))
        .andExpect(jsonPath("$.assignments[0].employee").value(ada));

    mockMvc
        .perform(
            as(
                mockMvc,
                delete("/api/rosters/%s/assignments/%s/%s".formatted(roster, shift, ada)),
                organisation,
                "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignments.length()").value(0));
  }

  /**
   * #33 end to end, through the real use case, its transaction and the real database. Two
   * Schedulers assign different people to one draft at once. The first is held open inside its
   * transaction, its assignment made but not committed, until the second request is waiting on the
   * database; only then does it commit. Both assignments must survive. Without the read for update,
   * the second request stored the empty draft it had read plus its own assignment, and the first
   * one's was lost with nothing said.
   */
  @Test
  @Timeout(value = STUCK_AFTER_SECONDS * 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void GivenTwoSchedulersAssigningToOneDraftAtOnce_WhenBothCommit_ThenBothAssignmentsAreKept()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String roster = generateRoster(organisation, "2026-11-23T00:00:00Z", "2026-11-24T00:00:00Z");
    String morning = defineShift(organisation, "2026-11-23T06:00:00Z", "2026-11-23T14:00:00Z", 1);
    String evening = defineShift(organisation, "2026-11-23T14:00:00Z", "2026-11-23T22:00:00Z", 1);
    String ada = addEmployee(organisation, "Ada");
    String grace = addEmployee(organisation, "Grace");
    OrganisationId acting = new OrganisationId(UUID.fromString(organisation));
    MockHttpServletRequestBuilder assigningGrace =
        as(mockMvc, post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
            .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(evening, grace));

    ExecutorService secondScheduler = Executors.newSingleThreadExecutor();
    AtomicReference<Future<?>> second = new AtomicReference<>();
    try {
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              held -> {
                planRosters.assign(
                    new TenantActor(UserId.newId(), acting, Role.SCHEDULER),
                    new AssignToShiftCommand(
                        acting,
                        new RosterId(UUID.fromString(roster)),
                        new ShiftId(UUID.fromString(morning)),
                        new EmployeeId(UUID.fromString(ada))));
                int first = jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
                second.set(
                    secondScheduler.submit(
                        () -> mockMvc.perform(assigningGrace).andExpect(status().isOk())));
                awaitSomebodyWaitingOn(first, second.get());
              });
      second.get().get(STUCK_AFTER_SECONDS, TimeUnit.SECONDS);
    } finally {
      secondScheduler.shutdownNow();
    }

    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignments.length()").value(2));
  }

  // --- #205: checking a draft against every rule without publishing it --------------------------

  private static final String ASSESSED_DAY_START = "2026-11-24T00:00:00Z";
  private static final String ASSESSED_DAY_END = "2026-11-25T00:00:00Z";

  private String assessment(String roster) {
    return "/api/rosters/" + roster + "/assessment";
  }

  /**
   * Over the real stack: a draft breaking a daily cap is checked, its violation comes back in the
   * shape a publication refusal uses, and the draft is still a draft afterwards. Passing also shows
   * the check runs in its read-only transaction without asking for anything it cannot have there.
   * The cap is invented: one hour a day, below the eight-hour shift the generator gives Ada.
   */
  @Test
  void GivenADraftBreakingADailyCap_WhenAssessed_ThenEachViolationIsStructuredAndItStaysADraft()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-11-24T08:00:00Z", "2026-11-24T16:00:00Z", 1);
    String roster = generateRoster(organisation, ASSESSED_DAY_START, ASSESSED_DAY_END);
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), organisation, "ADMIN")
                .content("{\"maximumDailyMinutes\":60}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(as(mockMvc, get(assessment(roster)), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roster").value(roster))
        .andExpect(jsonPath("$.violations.length()").value(1))
        .andExpect(jsonPath("$.violations[0].employee").value(ada))
        .andExpect(jsonPath("$.violations[0].constraint").value("maximum daily working time"))
        .andExpect(jsonPath("$.shortfalls.length()").value(0));

    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(jsonPath("$.status").value("DRAFT"));
  }

  @Test
  void GivenAShortShift_WhenAssessed_ThenTheShortfallIsListed() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String roster = generateRoster(organisation, ASSESSED_DAY_START, ASSESSED_DAY_END);
    String shift = defineShift(organisation, "2026-11-24T08:00:00Z", "2026-11-24T16:00:00Z", 2);

    mockMvc
        .perform(as(mockMvc, get(assessment(roster)), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.violations.length()").value(0))
        .andExpect(jsonPath("$.shortfalls.length()").value(1))
        .andExpect(jsonPath("$.shortfalls[0].shift").value(shift))
        .andExpect(jsonPath("$.shortfalls[0].required").value(2))
        .andExpect(jsonPath("$.shortfalls[0].assigned").value(0))
        .andExpect(jsonPath("$.shortfalls[0].shortBy").value(2));
  }

  /**
   * The whole chain of ADR-010 over HTTP: Ada says she would rather not work the day, through
   * workforce; the Scheduler marks the day's shift unpopular; the Admin weighs fairness and
   * preferences (invented weights); generation puts Ada on it, because she is the only person. The
   * check counts one unpopular shift for her and one preference gone against, and leaves the
   * unweighed disruption out of the total.
   */
  @Test
  void GivenWeightedObjectives_WhenADraftIsAssessed_ThenEachMeasureAndTheTotalAreListed()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + ada + "/preferences"), organisation, "ADMIN")
                .content(
                    "{\"start\":\"%s\",\"end\":\"%s\",\"strength\":\"PREFERS_NOT\"}"
                        .formatted(ASSESSED_DAY_START, ASSESSED_DAY_END)))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            as(mockMvc, post("/api/shifts"), organisation, "SCHEDULER")
                .content(
                    "{\"start\":\"2026-11-24T08:00:00Z\",\"end\":\"2026-11-24T16:00:00Z\","
                        + "\"zone\":\"UTC\",\"requiredHeadcount\":1,\"unpopular\":true}"))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            as(mockMvc, put("/api/objectives"), organisation, "ADMIN")
                .content("{\"fairness\":3,\"preferences\":2}"))
        .andExpect(status().isOk());
    String roster = generateRoster(organisation, ASSESSED_DAY_START, ASSESSED_DAY_END);

    mockMvc
        .perform(as(mockMvc, get(assessment(roster)), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.objectives.length()").value(3))
        .andExpect(jsonPath("$.objectives[0].objective").value("FAIRNESS"))
        .andExpect(jsonPath("$.objectives[0].measure").value(1))
        .andExpect(jsonPath("$.objectives[0].weight").value(3))
        .andExpect(jsonPath("$.objectives[1].objective").value("PREFERENCES"))
        .andExpect(jsonPath("$.objectives[1].measure").value(1))
        .andExpect(jsonPath("$.objectives[1].weight").value(2))
        .andExpect(jsonPath("$.objectives[2].objective").value("DISRUPTION"))
        .andExpect(jsonPath("$.objectives[2].measure").value(0))
        .andExpect(jsonPath("$.objectives[2].weight").isEmpty())
        .andExpect(jsonPath("$.weightedTotal").value(5));
  }

  @Test
  void GivenAnAdmin_WhenAssessingADraft_ThenItIsAnswered() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String roster = generateRoster(organisation, ASSESSED_DAY_START, ASSESSED_DAY_END);

    mockMvc
        .perform(as(mockMvc, get(assessment(roster)), organisation, "ADMIN"))
        .andExpect(status().isOk());
  }

  /** A draft is personal data about every colleague on it (R12). */
  @Test
  void GivenAnEmployee_WhenAssessingADraft_ThenItIsForbidden() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String roster = generateRoster(organisation, ASSESSED_DAY_START, ASSESSED_DAY_END);

    mockMvc
        .perform(asThemself(mockMvc, get(assessment(roster)), organisation, ada))
        .andExpect(status().isForbidden());
  }

  /** A 404, never a 403: a refusal must not confirm another organisation's roster exists. */
  @Test
  void GivenARosterOfAnotherOrganisation_WhenAssessed_ThenItIsNotFound() throws Exception {
    String ours = anOrganisation(mockMvc);
    String theirs = anOrganisation(mockMvc);
    String theirRoster = generateRoster(theirs, ASSESSED_DAY_START, ASSESSED_DAY_END);

    mockMvc
        .perform(as(mockMvc, get(assessment(theirRoster)), ours, "SCHEDULER"))
        .andExpect(status().isNotFound());
  }

  /** A published roster was judged under the rules version it records (Q9), not today's. */
  @Test
  void GivenAPublishedRoster_WhenAssessed_ThenItIsAConflict() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String roster = generateRoster(organisation, ASSESSED_DAY_START, ASSESSED_DAY_END);
    publish(organisation, roster);

    mockMvc
        .perform(as(mockMvc, get(assessment(roster)), organisation, "SCHEDULER"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Roster status forbids this"));
  }

  @Test
  void GivenAnAdminRole_WhenAssigningByHand_ThenItIsForbidden() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String roster = generateRoster(organisation, "2026-11-03T00:00:00Z", "2026-11-04T00:00:00Z");
    String shift = defineShift(organisation, "2026-11-03T08:00:00Z", "2026-11-03T16:00:00Z", 1);

    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/assignments"), organisation, "ADMIN")
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
                as(mockMvc, post("/api/shifts"), organisation, "SCHEDULER")
                    .content(
                        ("{\"start\":\"2026-11-04T08:00:00Z\",\"end\":\"2026-11-04T16:00:00Z\","
                                + "\"zone\":\"UTC\",\"requiredHeadcount\":1,\"requiredQualifications\":[\"%s\"]}")
                            .formatted(forklift)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString()
            .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
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
            as(mockMvc, post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(day, ada)))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(afternoon, ada)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Assignment refused"))
        .andExpect(jsonPath("$.detail").value(containsString("overlap")));
  }

  /**
   * A second press of the same assignment, as a double click sends. Nothing about the request is
   * wrong; the roster already holds it, so it is a conflict the board answers by reloading, and its
   * detail names neither the shift nor the person sent.
   */
  @Test
  void GivenSomebodyAlreadyOnTheShift_WhenAssignedToItAgain_ThenItIsAConflictRepeatingNothingSent()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String roster = generateRoster(organisation, "2026-11-18T00:00:00Z", "2026-11-19T00:00:00Z");
    String day = defineShift(organisation, "2026-11-18T08:00:00Z", "2026-11-18T16:00:00Z", 1);
    assign(organisation, roster, day, ada);

    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(day, ada)))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Already assigned"))
        .andExpect(jsonPath("$.detail").value(not(containsString(day))))
        .andExpect(jsonPath("$.detail").value(not(containsString(ada))));
  }

  // --- R35: R3 across every authoritative roster -----------------------------------------------
  //
  // Both weeks' drafts are generated before any shift exists, so they start empty and carry only
  // what each test puts on them. Generated later, next week's draft would pick up Sunday night
  // itself, and the refusal would be R3 within one roster rather than across two.

  private static final String SUNDAY_NIGHT_START = "2026-12-13T22:00:00Z";
  private static final String SUNDAY_NIGHT_END = "2026-12-14T06:00:00Z";

  private void assign(String organisation, String roster, String shift, String employee)
      throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(shift, employee)))
        .andExpect(status().isOk());
  }

  private void publish(String organisation, String roster) throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isOk());
  }

  @Test
  void
      GivenTheirNightOnLastWeeksPublishedRoster_WhenAssigningOverlappingWork_ThenItIsUnprocessable()
          throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String thisWeek = generateRoster(organisation, "2026-12-07T00:00:00Z", "2026-12-14T00:00:00Z");
    String nextWeek = generateRoster(organisation, "2026-12-14T00:00:00Z", "2026-12-21T00:00:00Z");
    String sundayNight = defineShift(organisation, SUNDAY_NIGHT_START, SUNDAY_NIGHT_END, 1);
    String mondayEarly =
        defineShift(organisation, "2026-12-14T00:00:00Z", "2026-12-14T08:00:00Z", 1);
    assign(organisation, thisWeek, sundayNight, ada);
    publish(organisation, thisWeek);

    mockMvc
        .perform(
            as(
                    mockMvc,
                    post("/api/rosters/" + nextWeek + "/assignments"),
                    organisation,
                    "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(mondayEarly, ada)))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Assignment refused"))
        .andExpect(jsonPath("$.detail").value(containsString(thisWeek)));
  }

  /**
   * The check that holds, over a real database. Both drafts were filled before either was
   * published, so assigning saw nothing; publication is judged under the lock against what is
   * really stored.
   */
  @Test
  void GivenTwoDraftsPuttingOnePersonInTwoPlaces_WhenTheSecondIsPublished_ThenItIsUnprocessable()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String thisWeek = generateRoster(organisation, "2026-12-07T00:00:00Z", "2026-12-14T00:00:00Z");
    String nextWeek = generateRoster(organisation, "2026-12-14T00:00:00Z", "2026-12-21T00:00:00Z");
    assign(
        organisation,
        thisWeek,
        defineShift(organisation, SUNDAY_NIGHT_START, SUNDAY_NIGHT_END, 1),
        ada);
    assign(
        organisation,
        nextWeek,
        defineShift(organisation, "2026-12-14T00:00:00Z", "2026-12-14T08:00:00Z", 1),
        ada);
    publish(organisation, nextWeek);

    mockMvc
        .perform(
            as(
                mockMvc,
                post("/api/rosters/" + thisWeek + "/publication"),
                organisation,
                "SCHEDULER"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.title").value("Roster breaks the rules"))
        .andExpect(jsonPath("$.violations.length()").value(1))
        .andExpect(jsonPath("$.violations[0].employee").value(ada))
        .andExpect(jsonPath("$.violations[0].constraint").value("overlapping work"))
        .andExpect(jsonPath("$.violations[0].detail").value(containsString(nextWeek)));
  }

  /**
   * R18 at publication, over a real database. Ada was free when she was assigned, and declared
   * afterwards that she cannot work that day. Nothing about the draft changed, so only publication
   * can catch it, and it refuses with that violation listed like any other (#205).
   */
  @Test
  void GivenUnavailabilityDeclaredAfterAssignment_WhenPublishing_ThenItIsRefusedWithThatViolation()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String roster = generateRoster(organisation, "2026-11-30T00:00:00Z", "2026-12-01T00:00:00Z");
    String shift = defineShift(organisation, "2026-11-30T08:00:00Z", "2026-11-30T16:00:00Z", 1);
    assign(organisation, roster, shift, ada);
    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, "ADMIN")
                .content("{\"start\":\"2026-11-30T00:00:00Z\",\"end\":\"2026-12-01T00:00:00Z\"}"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.title").value("Roster breaks the rules"))
        .andExpect(jsonPath("$.violations.length()").value(1))
        .andExpect(jsonPath("$.violations[0].employee").value(ada))
        .andExpect(jsonPath("$.violations[0].constraint").value("declared unavailability"))
        .andExpect(jsonPath("$.violations[0].detail").value(containsString(shift)));
  }

  /**
   * R35's first half: a shift straddling two roster periods may be carried by either roster, and by
   * both. Generating next week covers Sunday night again with the same person, which must not read
   * as being in two places at once.
   */
  @Test
  void GivenAShiftStraddlingIntoNextWeek_WhenEachWeekIsGeneratedAndPublished_ThenBothArePublished()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String sundayNight = defineShift(organisation, SUNDAY_NIGHT_START, SUNDAY_NIGHT_END, 1);
    String thisWeek = generateRoster(organisation, "2026-12-07T00:00:00Z", "2026-12-14T00:00:00Z");
    publish(organisation, thisWeek);
    String nextWeek = generateRoster(organisation, "2026-12-14T00:00:00Z", "2026-12-21T00:00:00Z");

    mockMvc
        .perform(
            as(
                mockMvc,
                post("/api/rosters/" + nextWeek + "/publication"),
                organisation,
                "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.assignments[0].shift").value(sundayNight))
        .andExpect(jsonPath("$.assignments[0].employee").value(ada));
  }

  /**
   * #130. Ada's Sunday evening on last week's published roster runs into next week's Sunday night,
   * so generating next week must leave her off that night: putting her on it would draft a roster
   * publication then refuses for R35. Nobody else can take it, so it is left short.
   */
  @Test
  void GivenTheirEveningOnLastWeeksPublishedRoster_WhenNextWeekIsGenerated_ThenItIsPublishable()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String thisWeek = generateRoster(organisation, "2026-12-07T00:00:00Z", "2026-12-14T00:00:00Z");
    assign(
        organisation,
        thisWeek,
        defineShift(organisation, "2026-12-13T18:00:00Z", "2026-12-13T23:00:00Z", 1),
        ada);
    publish(organisation, thisWeek);
    defineShift(organisation, SUNDAY_NIGHT_START, SUNDAY_NIGHT_END, 1);

    String nextWeek = generateRoster(organisation, "2026-12-14T00:00:00Z", "2026-12-21T00:00:00Z");

    mockMvc
        .perform(
            as(
                mockMvc,
                post("/api/rosters/" + nextWeek + "/publication"),
                organisation,
                "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignments.length()").value(0));
  }

  /**
   * #130, decided on 2026-10-22: whoever last week's published roster puts on a shift straddling
   * into next week stays on it when next week is generated, and counts toward its headcount, rather
   * than the shift being staffed again by whoever generation would have picked first.
   */
  @Test
  void GivenAStraddlingShiftStaffedOnLastWeeksPublishedRoster_WhenNextWeekIsGenerated_ThenTheyStay()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    String grace = addEmployee(organisation, "Grace");
    String thisWeek = generateRoster(organisation, "2026-12-07T00:00:00Z", "2026-12-14T00:00:00Z");
    String sundayNight = defineShift(organisation, SUNDAY_NIGHT_START, SUNDAY_NIGHT_END, 1);
    assign(organisation, thisWeek, sundayNight, grace);
    publish(organisation, thisWeek);

    String nextWeek = generateRoster(organisation, "2026-12-14T00:00:00Z", "2026-12-21T00:00:00Z");

    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + nextWeek), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignments.length()").value(1))
        .andExpect(jsonPath("$.assignments[0].shift").value(sundayNight))
        .andExpect(jsonPath("$.assignments[0].employee").value(grace));
  }

  /**
   * Somebody carried over who has since declared they cannot work that night cannot stay on it, and
   * leaving them off would have last week's roster and next week's disagree about who works it. So
   * the generation ends refused, naming them, and it is last week's roster that has to change.
   */
  @Test
  void GivenSomebodyCarriedOverWhoHasSinceDeclaredThemselvesAway_WhenGenerating_ThenItIsRefused()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String grace = addEmployee(organisation, "Grace");
    String thisWeek = generateRoster(organisation, "2026-12-07T00:00:00Z", "2026-12-14T00:00:00Z");
    String sundayNight = defineShift(organisation, SUNDAY_NIGHT_START, SUNDAY_NIGHT_END, 1);
    assign(organisation, thisWeek, sundayNight, grace);
    publish(organisation, thisWeek);
    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + grace + "/unavailability"), organisation, "ADMIN")
                .content("{\"start\":\"2026-12-14T00:00:00Z\",\"end\":\"2026-12-15T00:00:00Z\"}"))
        .andExpect(status().isNoContent());

    String started =
        mockMvc
            .perform(
                as(mockMvc, post("/api/rosters/generations"), organisation, "SCHEDULER")
                    .content(
                        "{\"start\":\"2026-12-14T00:00:00Z\",\"end\":\"2026-12-21T00:00:00Z\"}"))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();

    mockMvc
        .perform(
            as(
                mockMvc,
                get("/api/rosters/generations/" + JsonPath.read(started, "$.id")),
                organisation,
                "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REFUSED"))
        .andExpect(jsonPath("$.roster").doesNotExist())
        .andExpect(jsonPath("$.violations.length()").value(1))
        .andExpect(jsonPath("$.violations[0].employee").value(grace))
        .andExpect(jsonPath("$.violations[0].constraint").value("declared unavailability"))
        .andExpect(jsonPath("$.violations[0].detail").value(containsString(sundayNight)));
  }

  /**
   * ADR-046: asking for a draft is answered at once, 202, with where to read how the generation
   * stands, because a solve at the largest stated size takes minutes.
   */
  @Test
  void GivenAScheduler_WhenStartingAGeneration_ThenItIsAcceptedWithWhereToReadIt()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    String started =
        mockMvc
            .perform(
                as(mockMvc, post("/api/rosters/generations"), organisation, "SCHEDULER")
                    .content(
                        "{\"start\":\"2026-11-16T00:00:00Z\",\"end\":\"2026-11-17T00:00:00Z\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getHeader("Location");

    assertThat(started).startsWith("/api/rosters/generations/");
    mockMvc
        .perform(as(mockMvc, get(started), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));
  }

  @Test
  void GivenAnEmployee_WhenStartingAGeneration_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/generations"), anOrganisation(mockMvc), "EMPLOYEE")
                .content("{\"start\":\"2026-11-16T00:00:00Z\",\"end\":\"2026-11-17T00:00:00Z\"}"))
        .andExpect(status().isForbidden());
  }

  /** Another organisation's generation is as absent as one that never existed. */
  @Test
  void GivenAnotherOrganisationsGeneration_WhenRead_ThenItIsNotFound() throws Exception {
    String theirs = anOrganisation(mockMvc);
    String started =
        mockMvc
            .perform(
                as(mockMvc, post("/api/rosters/generations"), theirs, "SCHEDULER")
                    .content(
                        "{\"start\":\"2026-11-16T00:00:00Z\",\"end\":\"2026-11-17T00:00:00Z\"}"))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getHeader("Location");

    mockMvc
        .perform(as(mockMvc, get(started), anOrganisation(mockMvc), "SCHEDULER"))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenSomebodyNotInTheOrganisation_WhenAssigningThem_ThenItIsNotFound() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String stranger = addEmployee(anOrganisation(mockMvc), "Their Employee");
    String roster = generateRoster(organisation, "2026-11-05T00:00:00Z", "2026-11-06T00:00:00Z");
    String shift = defineShift(organisation, "2026-11-05T08:00:00Z", "2026-11-05T16:00:00Z", 1);

    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
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
            as(mockMvc, post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
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
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.assignments.length()").value(1));
  }

  @Test
  void GivenACompliantDraft_WhenPublished_ThenItRecordsTheConstraintVersion() throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-06T08:00:00Z", "2026-10-06T16:00:00Z", 1);
    // Invented: ten hours a day, which the eight-hour shift satisfies.
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), organisation, "ADMIN")
                .content("{\"maximumDailyMinutes\":600}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));

    String roster = generateRoster(organisation, "2026-10-06T00:00:00Z", "2026-10-07T00:00:00Z");

    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.validatedAgainstConstraintVersion").value(2));
  }

  /**
   * Q9 end to end: the version a published roster names reads back as the rules it passed, after
   * the organisation has revised them. Any member may see the rules (F21), so an Employee asks.
   */
  @Test
  void GivenRulesRevisedAfterPublication_WhenTheRosterVersionIsRead_ThenItIsTheRulesItPassed()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-11-16T08:00:00Z", "2026-11-16T16:00:00Z", 1);
    // Invented values, chosen to be recognisable in the response. They are not any statute's.
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), organisation, "ADMIN")
                .content("{\"maximumDailyMinutes\":601}"))
        .andExpect(status().isOk());
    String roster = generateRoster(organisation, "2026-11-16T00:00:00Z", "2026-11-17T00:00:00Z");
    String published =
        mockMvc
            .perform(
                as(
                    mockMvc,
                    post("/api/rosters/" + roster + "/publication"),
                    organisation,
                    "SCHEDULER"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    int version = JsonPath.read(published, "$.validatedAgainstConstraintVersion");
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), organisation, "ADMIN")
                .content("{\"maximumDailyMinutes\":602}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(as(mockMvc, get("/api/constraints/" + version), organisation, "EMPLOYEE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(version))
        .andExpect(jsonPath("$.maximumDailyMinutes").value(601));
  }

  @Test
  void GivenNoSuchConstraintVersion_WhenReadingIt_ThenTheMissIsAProblemDetail() throws Exception {
    mockMvc
        .perform(as(mockMvc, get("/api/constraints/99"), anOrganisation(mockMvc), "ADMIN"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Not found"))
        .andExpect(jsonPath("$.detail").value("No such resource."));
  }

  /**
   * Every organisation's second version is called 2, so the only thing standing between us and
   * theirs is the query's scope. Reading it must look exactly like reading a version nobody wrote.
   */
  @Test
  void GivenAnotherOrganisationsVersion_WhenReadingIt_ThenTheAnswerIsTheOneForNoVersionAtAll()
      throws Exception {
    // Invented: any rule at all, so that the other organisation holds a version 2.
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), anOrganisation(mockMvc), "ADMIN")
                .content("{\"maximumConsecutiveDays\":3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
    String ours = anOrganisation(mockMvc);

    Map<String, Object> theirs = notFoundWithoutInstance("/api/constraints/2", ours);
    Map<String, Object> none = notFoundWithoutInstance("/api/constraints/99", ours);

    assertThat(theirs).isEqualTo(none);
  }

  /**
   * Reaching the binder at all proves the route is named in the security chain: an unnamed one is
   * refused before it is dispatched (ADR-032), and would answer 403 here instead.
   */
  @Test
  void GivenAConstraintVersionThatIsNotANumber_WhenReadingIt_ThenItIsABadRequest()
      throws Exception {
    mockMvc
        .perform(as(mockMvc, get("/api/constraints/latest"), anOrganisation(mockMvc), "ADMIN"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void GivenUnavailableStaff_WhenGeneratingARoster_ThenTheShiftIsLeftUncovered() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, "ADMIN")
                .content("{\"start\":\"2026-10-07T00:00:00Z\",\"end\":\"2026-10-08T00:00:00Z\"}"))
        .andExpect(status().isNoContent());
    defineShift(organisation, "2026-10-07T08:00:00Z", "2026-10-07T16:00:00Z", 1);

    String roster = generateRoster(organisation, "2026-10-07T00:00:00Z", "2026-10-08T00:00:00Z");

    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignments.length()").value(0));
  }

  /** The other half of R18: an absence withdrawn (F26) no longer keeps anybody off a roster. */
  @Test
  void GivenUnavailabilityWithdrawn_WhenGeneratingARoster_ThenTheShiftIsCovered() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    mockMvc
        .perform(
            as(mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, "ADMIN")
                .content("{\"start\":\"2026-10-10T00:00:00Z\",\"end\":\"2026-10-11T00:00:00Z\"}"))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            as(
                mockMvc,
                delete("/api/employees/" + ada + "/unavailability")
                    .param("start", "2026-10-10T00:00:00Z")
                    .param("end", "2026-10-11T00:00:00Z"),
                organisation,
                "ADMIN"))
        .andExpect(status().isNoContent());
    defineShift(organisation, "2026-10-10T08:00:00Z", "2026-10-10T16:00:00Z", 1);

    String roster = generateRoster(organisation, "2026-10-10T00:00:00Z", "2026-10-11T00:00:00Z");

    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignments.length()").value(1));
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenGeneratingARoster_ThenTheyAreNeverUsed()
      throws Exception {
    String ours = anOrganisation(mockMvc);
    addEmployee(anOrganisation(mockMvc), "Their Employee");
    defineShift(ours, "2026-10-08T08:00:00Z", "2026-10-08T16:00:00Z", 1);

    String roster = generateRoster(ours, "2026-10-08T00:00:00Z", "2026-10-09T00:00:00Z");

    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), ours, "SCHEDULER"))
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
        .perform(as(mockMvc, get("/api/rosters/" + roster), anOrganisation(mockMvc), "SCHEDULER"))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenNoSuchRoster_WhenReadingIt_ThenTheMissIsAProblemDetail() throws Exception {
    mockMvc
        .perform(
            as(
                mockMvc,
                get("/api/rosters/" + UUID.randomUUID()),
                anOrganisation(mockMvc),
                "SCHEDULER"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Not found"))
        .andExpect(jsonPath("$.detail").value("No such resource."));
  }

  /**
   * Compared whole rather than field by field, so a property one answer carries and the other does
   * not would tell them apart here too. Only {@code instance} is set aside: it repeats the path
   * that was asked for, and the two requests ask for different paths.
   */
  @Test
  void GivenARosterOfAnotherOrganisation_WhenReadingIt_ThenTheAnswerIsTheOneForNoRosterAtAll()
      throws Exception {
    String roster =
        generateRoster(anOrganisation(mockMvc), "2026-10-10T00:00:00Z", "2026-10-11T00:00:00Z");
    String ours = anOrganisation(mockMvc);

    Map<String, Object> theirs = notFoundWithoutInstance("/api/rosters/" + roster, ours);
    Map<String, Object> none = notFoundWithoutInstance("/api/rosters/" + UUID.randomUUID(), ours);

    assertThat(theirs).isEqualTo(none);
  }

  private Map<String, Object> notFoundWithoutInstance(String path, String organisation)
      throws Exception {
    String body =
        mockMvc
            .perform(as(mockMvc, get(path), organisation, "SCHEDULER"))
            .andExpect(status().isNotFound())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> answer = new HashMap<>(JsonPath.<Map<String, Object>>read(body, "$"));
    answer.remove("instance");
    return answer;
  }

  @Test
  void GivenAnEmployeeRole_WhenDefiningAShift_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/shifts"), anOrganisation(mockMvc), "EMPLOYEE")
                .content(
                    "{\"start\":\"2026-10-10T08:00:00Z\",\"end\":\"2026-10-10T16:00:00Z\","
                        + "\"zone\":\"UTC\",\"requiredHeadcount\":1}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnInvertedPeriod_WhenDefiningAShift_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/shifts"), anOrganisation(mockMvc), "SCHEDULER")
                .content(
                    "{\"start\":\"2026-10-11T16:00:00Z\",\"end\":\"2026-10-11T08:00:00Z\","
                        + "\"zone\":\"UTC\",\"requiredHeadcount\":1}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenNoConstraintsConfigured_WhenReadingThem_ThenAnEmptyFirstVersionIsReturned()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    mockMvc
        .perform(as(mockMvc, get("/api/constraints"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.constraints.length()").value(0));
  }

  /**
   * #96: rules stored with two of a kind are a fault in stored data, not the caller's mistake.
   * Reading them is answered by no handler, so the caller gets a 500 and the failure is logged,
   * rather than a 400 blaming the request. Spring routes an exception by its cause when its own
   * type has no handler, and the domain's refusal of such a set is answered 400, so it must not
   * travel as one.
   *
   * <p>Since V14 the schema refuses two of a kind (R24), so the rows are stored here with that
   * constraint dropped, inside a transaction that is rolled back, and the request is made inside
   * it. The use case opens its transaction with {@code REQUIRED} propagation ({@code
   * TransactionalUseCases}), and MockMvc runs it on this thread, so it joins this one: it reads
   * rows no other connection can see, and no other test sees the schema without the constraint. Had
   * it not joined, it would wait on the dropped constraint's lock until the timeout.
   */
  @Test
  @Timeout(value = STUCK_AFTER_SECONDS * 2, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void GivenRulesStoredWithTwoOfAKind_WhenReadingThem_ThenNoHandlerAnswersItAsTheCallersMistake()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    MockHttpServletRequestBuilder readingTheRules =
        as(mockMvc, get("/api/constraints"), organisation, "ADMIN");

    Throwable unanswered =
        new TransactionTemplate(transactions)
            .execute(
                rolledBack -> {
                  rolledBack.setRollbackOnly();
                  jdbc.sql(
                          "ALTER TABLE working_time_constraint"
                              + " DROP CONSTRAINT working_time_constraint_one_of_each_kind")
                      .update();
                  jdbc.sql("INSERT INTO constraint_set (organisation_id, version) VALUES (:id, 2)")
                      .param("id", UUID.fromString(organisation))
                      .update();
                  // Invented: two daily caps, of 437 minutes and of 511, figures from no statute.
                  for (int ordinal = 0; ordinal < 2; ordinal++) {
                    jdbc.sql(
                            """
                            INSERT INTO working_time_constraint
                                (organisation_id, version, ordinal, kind, duration_seconds,
                                 day_count)
                            VALUES (:id, 2, :ordinal, 'MAXIMUM_DAILY_WORKING_TIME', :seconds, NULL)
                            """)
                        .param("id", UUID.fromString(organisation))
                        .param("ordinal", ordinal)
                        .param("seconds", TimeUnit.MINUTES.toSeconds(437 + 74L * ordinal))
                        .update();
                  }
                  return catchThrowable(() -> mockMvc.perform(readingTheRules));
                });

    assertThat(unanswered)
        .describedAs("left to the server's error handling, so a 500, not a 4xx")
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .rootCause()
        .describedAs("the rows this transaction holds were read, so the use case joined it")
        .hasMessageContaining("maximum daily working time is given twice");
  }

  /**
   * #96: a row the domain refuses to load is a fault in stored data, not the caller's mistake.
   * Reading it is answered by no handler, so the caller gets a 500 and the failure is logged,
   * rather than a 4xx blaming the request.
   *
   * <p>A generation stored REFUSED with no rule it broke is such a row: the schema does not hold
   * that a refusal names what it broke (V14), and {@code RosterGeneration} refuses to be loaded
   * without one.
   */
  @Test
  void
      GivenARefusalStoredWithNothingBroken_WhenPollingIt_ThenNoHandlerAnswersItAsTheCallersMistake()
          throws Exception {
    String organisation = anOrganisation(mockMvc);
    UUID generation = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO roster_generation (id, organisation_id, starts_at, ends_at, requested_at,
                                           lost_at, status, finished_at)
            VALUES (:id, :organisation, '2026-11-09T00:00:00Z', '2026-11-16T00:00:00Z',
                    '2026-11-01T09:00:00Z', '2026-11-01T09:15:00Z', 'REFUSED',
                    '2026-11-01T09:01:00Z')
            """)
        .param("id", generation)
        .param("organisation", UUID.fromString(organisation))
        .update();

    Throwable unanswered =
        catchThrowable(
            () ->
                mockMvc.perform(
                    as(
                        mockMvc,
                        get("/api/rosters/generations/" + generation),
                        organisation,
                        "SCHEDULER")));

    assertThat(unanswered)
        .describedAs("left to the server's error handling, so a 500, not a 4xx")
        .hasRootCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void GivenAConstraintThatCannotBeSatisfied_WhenGenerating_ThenNothingIsAssigned()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    // Invented: one hour a day.
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), organisation, "ADMIN")
                .content("{\"maximumDailyMinutes\":60}"))
        .andExpect(status().isOk());
    defineShift(organisation, "2026-10-12T08:00:00Z", "2026-10-12T16:00:00Z", 1);

    String roster = generateRoster(organisation, "2026-10-12T00:00:00Z", "2026-10-13T00:00:00Z");

    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        // An eight-hour shift cannot be filled under a one-hour daily cap.
        .andExpect(jsonPath("$.assignments.length()").value(0));
  }

  @Test
  void GivenAScheduler_WhenConfiguringConstraints_ThenItIsForbidden() throws Exception {
    // Invented: the role is refused, so the value is never stored.
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), anOrganisation(mockMvc), "SCHEDULER")
                .content("{\"maximumDailyMinutes\":600}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnEmployee_WhenConfiguringConstraints_ThenItIsForbidden() throws Exception {
    // Invented: the role is refused, so the value is never stored.
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), anOrganisation(mockMvc), "EMPLOYEE")
                .content("{\"maximumDailyMinutes\":600}"))
        .andExpect(status().isForbidden());
  }

  /** Publishing builds a roster, which is the Scheduler's alone; an Admin manages the rules. */
  @Test
  void GivenAnAdminRole_WhenPublishing_ThenItIsForbiddenAndItStaysADraft() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String roster = generateRoster(organisation, "2026-10-14T00:00:00Z", "2026-10-15T00:00:00Z");

    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/publication"), organisation, "ADMIN"))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"));
  }

  @Test
  void GivenAnEmployeeRole_WhenPublishing_ThenItIsForbiddenAndItStaysADraft() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String roster = generateRoster(organisation, "2026-10-14T00:00:00Z", "2026-10-15T00:00:00Z");

    mockMvc
        .perform(
            asThemself(mockMvc, post("/api/rosters/" + roster + "/publication"), organisation, ada))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"));
  }

  /**
   * Nothing about the request is wrong; the roster has moved past the state it asks about, which
   * the caller resolves by reloading. A conflict, then, not a bad request (ADR-041).
   */
  @Test
  void GivenAPublishedRoster_WhenPublishedAgain_ThenItIsAConflict() throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-13T08:00:00Z", "2026-10-13T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-10-13T00:00:00Z", "2026-10-14T00:00:00Z");
    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Roster status forbids this"));
  }

  /**
   * What an edit that waited on a publication of its own draft now finds (ADR-041): the roster is
   * published, and a published roster is superseded rather than edited (R6).
   */
  @Test
  void GivenAPublishedRoster_WhenAssigningToIt_ThenItIsAConflict() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String roster = generateRoster(organisation, "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z");
    String shift = defineShift(organisation, "2026-10-20T08:00:00Z", "2026-10-20T16:00:00Z", 1);
    String ada = addEmployee(organisation, "Ada");
    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(shift, ada)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Roster status forbids this"));
  }

  @Test
  void GivenTwoOrganisations_WhenEachConfiguresRules_ThenNeitherSeesTheOther() throws Exception {
    String first = anOrganisation(mockMvc);
    String second = anOrganisation(mockMvc);
    // Invented: any rule at all, so the first organisation has one to leak.
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), first, "ADMIN")
                .content("{\"maximumConsecutiveDays\":3}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(as(mockMvc, get("/api/constraints"), second, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.constraints.length()").value(0));
    // And the rule was stored, so the empty answer above is isolation rather than a write lost.
    mockMvc
        .perform(as(mockMvc, get("/api/constraints"), first, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maximumConsecutiveDays").value(3));
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
            as(mockMvc, put("/api/constraints"), organisation, "ADMIN")
                .content("{\"maximumDailyMinutes\":60}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
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
            as(mockMvc, get("/api/shifts"), organisation, "SCHEDULER")
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
            as(mockMvc, get("/api/shifts"), anOrganisation(mockMvc), "SCHEDULER")
                .param("from", "2026-10-22T00:00:00Z")
                .param("to", "2026-10-23T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void GivenNoSpan_WhenListingShifts_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(as(mockMvc, get("/api/shifts"), anOrganisation(mockMvc), "SCHEDULER"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAGeneratedRoster_WhenListingRosters_ThenItIsReturned() throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-23T08:00:00Z", "2026-10-23T16:00:00Z", 1);
    generateRoster(organisation, "2026-10-23T00:00:00Z", "2026-10-24T00:00:00Z");

    mockMvc
        .perform(as(mockMvc, get("/api/rosters"), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].status").value("DRAFT"));
  }

  /** F11a: an Admin sees the organisation's rosters, as a Scheduler does (R12). */
  @Test
  void GivenAGeneratedRoster_WhenAnAdminListsRosters_ThenItIsReturned() throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-23T08:00:00Z", "2026-10-23T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-10-23T00:00:00Z", "2026-10-24T00:00:00Z");

    mockMvc
        .perform(as(mockMvc, get("/api/rosters"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(roster));
  }

  /** F11a, one roster at a time. */
  @Test
  void GivenAGeneratedRoster_WhenAnAdminReadsIt_ThenItIsReturnedWithItsAssignments()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-23T08:00:00Z", "2026-10-23T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-10-23T00:00:00Z", "2026-10-24T00:00:00Z");

    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignments.length()").value(1))
        .andExpect(jsonPath("$.assignments[0].employee").value(ada));
  }

  @Test
  void GivenRostersOfAnotherOrganisation_WhenListingThem_ThenNothingLeaks() throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-24T08:00:00Z", "2026-10-24T16:00:00Z", 1);
    generateRoster(organisation, "2026-10-24T00:00:00Z", "2026-10-25T00:00:00Z");

    mockMvc
        .perform(as(mockMvc, get("/api/rosters"), anOrganisation(mockMvc), "SCHEDULER"))
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
            as(mockMvc, put("/api/constraints"), organisation, "ADMIN")
                .content("{\"minimumRestMinutes\":661,\"maximumConsecutiveDays\":5}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(as(mockMvc, get("/api/constraints"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        // Revising replaces the whole set, so a caller changing one rule must be able to read the
        // others back or it will silently delete them.
        .andExpect(jsonPath("$.minimumRestMinutes").value(661))
        .andExpect(jsonPath("$.maximumConsecutiveDays").value(5))
        .andExpect(jsonPath("$.maximumDailyMinutes").doesNotExist());
  }

  /**
   * F21: any member sees the rules people are rostered under. The values are invented, chosen to be
   * recognisable in the response, and are not any statute's.
   */
  @Test
  void GivenConfiguredRules_WhenAnEmployeeReadsThem_ThenTheirThresholdsAreShown() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), organisation, "ADMIN")
                .content("{\"minimumRestMinutes\":662}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(asThemself(mockMvc, get("/api/constraints"), organisation, ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.minimumRestMinutes").value(662));
  }

  /** F21, as a Scheduler, who builds rosters under these rules but may not change them (F10). */
  @Test
  void GivenConfiguredRules_WhenASchedulerReadsThem_ThenTheirThresholdsAreShown() throws Exception {
    String organisation = anOrganisation(mockMvc);
    // Invented, as above.
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), organisation, "ADMIN")
                .content("{\"maximumConsecutiveDays\":4}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(as(mockMvc, get("/api/constraints"), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maximumConsecutiveDays").value(4));
  }

  @Test
  void GivenAnEmployee_WhenListingRosters_ThenItIsForbidden() throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-25T08:00:00Z", "2026-10-25T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-10-25T00:00:00Z", "2026-10-26T00:00:00Z");

    // A roster says who works when, which is personal data about every colleague.
    mockMvc
        .perform(as(mockMvc, get("/api/rosters"), organisation, "EMPLOYEE"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "EMPLOYEE"))
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
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.start").value("2026-10-27T00:00:00Z"))
        .andExpect(jsonPath("$.end").value("2026-10-28T00:00:00Z"));
  }

  @Test
  void GivenABodyWithoutAPeriod_WhenDefiningAShift_ThenItIsABadRequestNotAServerError()
      throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/shifts"), anOrganisation(mockMvc), "SCHEDULER")
                .content("{\"zone\":\"UTC\",\"requiredHeadcount\":1}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenAQualificationNobodyDefined_WhenDefiningAShift_ThenItIsRejected() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/shifts"), anOrganisation(mockMvc), "SCHEDULER")
                .content(
                    "{\"start\":\"2026-10-05T08:00:00Z\",\"end\":\"2026-10-05T16:00:00Z\","
                        + "\"zone\":\"UTC\",\"requiredHeadcount\":1,\"requiredQualifications\":[\"%s\"]}"
                            .formatted(UUID.randomUUID())))
        .andExpect(status().isBadRequest());
  }

  @Test
  void GivenTheOperator_WhenReadingAnOrganisationsRoster_ThenItIsForbidden() throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-19T08:00:00Z", "2026-10-19T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-10-19T00:00:00Z", "2026-10-20T00:00:00Z");

    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "OPERATOR"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenATenantActor_WhenReadingAnotherOrganisationsRoster_ThenItIsNotFound() throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
    defineShift(organisation, "2026-10-20T08:00:00Z", "2026-10-20T16:00:00Z", 1);
    String roster = generateRoster(organisation, "2026-10-20T00:00:00Z", "2026-10-21T00:00:00Z");

    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), anOrganisation(mockMvc), "SCHEDULER"))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenADefinedShift_WhenFollowingItsLocation_ThenItIsFound() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String location =
        mockMvc
            .perform(
                as(mockMvc, post("/api/shifts"), organisation, "SCHEDULER")
                    .content(
                        "{\"start\":\"2026-11-16T08:00:00Z\",\"end\":\"2026-11-16T16:00:00Z\",\"zone\":\"UTC\",\"requiredHeadcount\":2}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader("Location");

    // Any member, as for the listing: a shift is work that needs covering, not a person.
    mockMvc
        .perform(as(mockMvc, get(location), organisation, "EMPLOYEE"))
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
        .perform(as(mockMvc, get("/api/shifts/" + theirs), anOrganisation(mockMvc), "SCHEDULER"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  // --- R36: a shift's working-time days are counted in its own zone ----------------------------

  /** Fixed, so a refusal can be asserted exactly and can never carry what the caller sent. */
  private static final String ZONE_REFUSAL =
      "zone must name an IANA time zone, such as Europe/London";

  private String defineShiftIn(String organisation, String zone, String start, String end)
      throws Exception {
    String body =
        mockMvc
            .perform(
                as(mockMvc, post("/api/shifts"), organisation, "SCHEDULER")
                    .content(
                        "{\"start\":\"%s\",\"end\":\"%s\",\"zone\":\"%s\",\"requiredHeadcount\":1}"
                            .formatted(start, end, zone)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.zone").value(zone))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
  }

  @Test
  void GivenAZone_WhenDefiningAShift_ThenItIsEchoedAndReadBack() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String shift =
        defineShiftIn(organisation, "Asia/Tokyo", "2026-12-01T00:00:00Z", "2026-12-01T08:00:00Z");

    mockMvc
        .perform(as(mockMvc, get("/api/shifts/" + shift), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.zone").value("Asia/Tokyo"));
  }

  /** ADR-010: the Scheduler says which shifts are unpopular, and the mark is kept as said. */
  @Test
  void GivenAShiftMarkedUnpopular_WhenDefined_ThenTheMarkIsEchoedAndReadBack() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String body =
        mockMvc
            .perform(
                as(mockMvc, post("/api/shifts"), organisation, "SCHEDULER")
                    .content(
                        "{\"start\":\"2026-12-05T22:00:00Z\",\"end\":\"2026-12-06T06:00:00Z\","
                            + "\"zone\":\"UTC\",\"requiredHeadcount\":1,\"unpopular\":true}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.unpopular").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String shift = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

    mockMvc
        .perform(as(mockMvc, get("/api/shifts/" + shift), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unpopular").value(true));
  }

  /** Absent is not marked: nobody said the shift is unpopular, so fairness does not count it. */
  @Test
  void GivenNoMark_WhenDefiningAShift_ThenItIsNotUnpopular() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String shift = defineShift(organisation, "2026-12-07T08:00:00Z", "2026-12-07T16:00:00Z", 1);

    mockMvc
        .perform(as(mockMvc, get("/api/shifts/" + shift), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.unpopular").value(false));
  }

  /**
   * No default. A zone the caller did not send is a zone a developer chose, and it would count
   * somebody's days in the wrong place without anybody noticing.
   */
  @Test
  void GivenNoZone_WhenDefiningAShift_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/shifts"), anOrganisation(mockMvc), "SCHEDULER")
                .content(
                    "{\"start\":\"2026-12-02T08:00:00Z\",\"end\":\"2026-12-02T16:00:00Z\","
                        + "\"requiredHeadcount\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value(ZONE_REFUSAL));
  }

  /** ADR-024: a refusal never repeats what was sent. */
  @Test
  void GivenAZoneThatDoesNotExist_WhenDefiningAShift_ThenNothingSentIsEchoed() throws Exception {
    String unmistakable = "Nowhere/Zz9PluralZAlpha";

    String body =
        mockMvc
            .perform(
                as(mockMvc, post("/api/shifts"), anOrganisation(mockMvc), "SCHEDULER")
                    .content(
                        ("{\"start\":\"2026-12-03T08:00:00Z\",\"end\":\"2026-12-03T16:00:00Z\","
                                + "\"zone\":\"%s\",\"requiredHeadcount\":1}")
                            .formatted(unmistakable)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value(ZONE_REFUSAL))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain(unmistakable);
  }

  /**
   * A raw offset is a valid Java zone but not a place: it ignores daylight saving, so a shift
   * entered as +01:00 would have its days counted an hour wrong half the year (ADR-034). Every
   * notation {@code ZoneId.of} would take for one is refused.
   */
  @ParameterizedTest
  @ValueSource(strings = {"+01:00", "UTC+01:00", "GMT+1"})
  void GivenARawOffsetForAZone_WhenDefiningAShift_ThenItIsABadRequest(String offset)
      throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/shifts"), anOrganisation(mockMvc), "SCHEDULER")
                .content(
                    ("{\"start\":\"2026-12-04T08:00:00Z\",\"end\":\"2026-12-04T16:00:00Z\","
                            + "\"zone\":\"%s\",\"requiredHeadcount\":1}")
                        .formatted(offset)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(ZONE_REFUSAL));
  }

  /**
   * The time zone database's own fixed offsets are accepted, because a browser on a machine set to
   * one reports exactly this name, and its typed times were read in it (ADR-034).
   */
  @Test
  void GivenTheDatabasesOwnFixedOffsetZone_WhenDefiningAShift_ThenItIsAccepted() throws Exception {
    defineShiftIn(
        anOrganisation(mockMvc), "Etc/GMT-3", "2026-12-04T08:00:00Z", "2026-12-04T16:00:00Z");
  }

  /**
   * The issue's done-when, end to end. Both shifts are on the 6th in Tokyo, twelve hours there, so
   * an invented ten-hour cap refuses the roster. Counted in UTC they would fall seven hours on the
   * 5th and five on the 6th, and it would publish.
   */
  @Test
  void GivenTwoShiftsOnOneDayInTheirOwnZone_WhenPublishingUnderADailyCap_ThenItIsRefused()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada");
    String roster = generateRoster(organisation, "2026-12-05T00:00:00Z", "2026-12-07T00:00:00Z");
    String lateNight =
        defineShiftIn(organisation, "Asia/Tokyo", "2026-12-05T15:00:00Z", "2026-12-05T21:00:00Z");
    String morning =
        defineShiftIn(organisation, "Asia/Tokyo", "2026-12-05T23:00:00Z", "2026-12-06T05:00:00Z");
    assign(organisation, roster, lateNight, ada);
    assign(organisation, roster, morning, ada);
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), organisation, "ADMIN")
                .content("{\"maximumDailyMinutes\":600}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.violations.length()").value(1))
        .andExpect(jsonPath("$.violations[0].employee").value(ada))
        .andExpect(jsonPath("$.violations[0].constraint").value("maximum daily working time"));
  }
}
