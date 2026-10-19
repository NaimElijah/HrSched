package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.as;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.asThemself;
import static io.github.naimelijah.hrsched.shared.testsupport.RacingTransactions.STUCK_AFTER_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
    String body =
        mockMvc
            .perform(
                as(mockMvc, post("/api/rosters"), organisation, "SCHEDULER")
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

  /**
   * Returns once some other backend is waiting on a lock {@code holder} holds, or once {@code
   * other} has finished without ever waiting - which is the failure the caller is testing for, and
   * should fail on its assertion rather than hang.
   */
  private void awaitSomebodyWaitingOn(int holder, Future<?> other) {
    long giveUpAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(STUCK_AFTER_SECONDS);
    while (!other.isDone()
        && !jdbc.sql(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity"
                    + " WHERE :holder = ANY (pg_blocking_pids(pid)))")
            .param("holder", holder)
            .query(Boolean.class)
            .single()) {
      if (System.nanoTime() > giveUpAt) {
        throw new IllegalStateException("The second Scheduler never reached the database");
      }
    }
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
        .andExpect(jsonPath("$.title").value("Assignment refused"))
        .andExpect(jsonPath("$.detail").value(containsString(nextWeek)));
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

  @Test
  void GivenAConstraintThatCannotBeSatisfied_WhenGenerating_ThenNothingIsAssigned()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    addEmployee(organisation, "Ada");
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
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), anOrganisation(mockMvc), "SCHEDULER")
                .content("{\"maximumDailyMinutes\":600}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAnEmployee_WhenConfiguringConstraints_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), anOrganisation(mockMvc), "EMPLOYEE")
                .content("{\"maximumDailyMinutes\":600}"))
        .andExpect(status().isForbidden());
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
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), first, "ADMIN")
                .content("{\"maximumConsecutiveDays\":3}"))
        .andExpect(status().isOk());

    mockMvc
        .perform(as(mockMvc, get("/api/constraints"), second, "ADMIN"))
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
