package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.as;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.asThemself;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Drives a swap over HTTP, end to end and across three contexts.
 *
 * <p>This is the widest path in the system: onboard a tenant in {@code access}, hire people in
 * {@code workforce}, roster and publish in {@code scheduling}, hand a shift over, and have the
 * colleague find out about it in {@code notifications}. It is also the only test where a
 * notification inbox has anything in it, because nothing else puts anything there — raising one is
 * deliberately not an endpoint.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class SwapHttpTest {

  @Autowired private MockMvc mockMvc;

  /** Performs a request that must create something, and returns the body it answered with. */
  private String created(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc
        .perform(request)
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String addEmployee(String organisation, String name) throws Exception {
    return JsonPath.read(
        created(
            as(mockMvc, post("/api/employees"), organisation, "ADMIN")
                .content("{\"name\":\"%s\"}".formatted(name))),
        "$.id");
  }

  private String defineShift(String organisation, String start, String end) throws Exception {
    return JsonPath.read(
        created(
            as(mockMvc, post("/api/shifts"), organisation, "SCHEDULER")
                .content(
                    "{\"start\":\"%s\",\"end\":\"%s\",\"zone\":\"UTC\",\"requiredHeadcount\":1}"
                        .formatted(start, end))),
        "$.id");
  }

  /** A published roster carrying one person on one shift, and the shift it covers. */
  private record Rostered(String roster, String shift) {}

  /**
   * Builds that roster in an order that leaves nothing to the generator: the draft is created
   * before any shift exists, so it comes back empty and the only assignment on it is the one made
   * here. The other order looks equivalent and is not - generation would fill the shift first, and
   * the assignment below would then be a duplicate.
   */
  private Rostered publishedFor(String organisation, String employee) throws Exception {
    String roster =
        JsonPath.read(
            created(
                as(mockMvc, post("/api/rosters"), organisation, "SCHEDULER")
                    .content(
                        "{\"start\":\"2026-12-07T00:00:00Z\",\"end\":\"2026-12-08T00:00:00Z\"}")),
            "$.id");
    String shift = defineShift(organisation, "2026-12-07T08:00:00Z", "2026-12-07T16:00:00Z");
    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(shift, employee)))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isOk());
    return new Rostered(roster, shift);
  }

  /**
   * The atomicity ADR-022 recorded as owed, and ADR-023 pays.
   *
   * <p>Approving a swap saves a replacement roster and then publishes it, and publication is what
   * refuses a roster that breaks a rule. Without a transaction the refusal happens after the
   * replacement has already been stored, leaving a draft nobody asked for and nobody will finish.
   * With one, the whole use case is a unit of work and the refusal takes the draft with it.
   *
   * <p>Counting rosters is the assertion, because the orphan is invisible from every other angle:
   * the request is still pending and the original still published either way.
   */
  @Test
  void GivenASwapThatBreaksARule_WhenApprovalIsRefused_ThenNoReplacementDraftIsLeftBehind()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada Lovelace");
    String grace = addEmployee(organisation, "Grace Hopper");
    Rostered rostered = publishedForBoth(organisation, ada, grace);
    String swap =
        JsonPath.read(
            created(
                asThemself(mockMvc, post("/api/swaps"), organisation, ada)
                    .content(
                        "{\"roster\":\"%s\",\"shift\":\"%s\",\"requester\":\"%s\",\"counterparty\":\"%s\"}"
                            .formatted(rostered.roster(), rostered.shift(), ada, grace))),
            "$.id");
    // Invented: ten hours a day, which Grace covering both eight-hour shifts cannot satisfy.
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), organisation, "ADMIN")
                .content("{\"maximumDailyMinutes\":600}"))
        .andExpect(status().isOk());
    int before = rosterCount(organisation);

    mockMvc
        .perform(as(mockMvc, post("/api/swaps/" + swap + "/approval"), organisation, "SCHEDULER"))
        .andExpect(status().isUnprocessableEntity());

    assertThat(rosterCount(organisation))
        .describedAs("a refused approval leaves no half-built roster behind")
        .isEqualTo(before);
  }

  private int rosterCount(String organisation) throws Exception {
    List<Object> rosters =
        JsonPath.read(
            mockMvc
                .perform(as(mockMvc, get("/api/rosters"), organisation, "SCHEDULER"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$");
    return rosters.size();
  }

  /** A published roster carrying both people, each on their own shift on the same day. */
  private Rostered publishedForBoth(String organisation, String first, String second)
      throws Exception {
    String roster =
        JsonPath.read(
            created(
                as(mockMvc, post("/api/rosters"), organisation, "SCHEDULER")
                    .content(
                        "{\"start\":\"2026-12-07T00:00:00Z\",\"end\":\"2026-12-08T00:00:00Z\"}")),
            "$.id");
    String morning = defineShift(organisation, "2026-12-07T00:00:00Z", "2026-12-07T08:00:00Z");
    String afternoon = defineShift(organisation, "2026-12-07T08:00:00Z", "2026-12-07T16:00:00Z");
    assign(organisation, roster, morning, first);
    assign(organisation, roster, afternoon, second);
    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isOk());
    return new Rostered(roster, morning);
  }

  private void assign(String organisation, String roster, String shift, String employee)
      throws Exception {
    mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(shift, employee)))
        .andExpect(status().isOk());
  }

  @Test
  void GivenPublishedWork_WhenASwapIsRequestedAndApproved_ThenTheRosterAndBothInboxesFollow()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada Lovelace");
    String grace = addEmployee(organisation, "Grace Hopper");
    Rostered rostered = publishedFor(organisation, ada);
    String shift = rostered.shift();
    String roster = rostered.roster();

    String swap =
        JsonPath.read(
            created(
                asThemself(mockMvc, post("/api/swaps"), organisation, ada)
                    .content(
                        "{\"roster\":\"%s\",\"shift\":\"%s\",\"requester\":\"%s\",\"counterparty\":\"%s\"}"
                            .formatted(roster, shift, ada, grace))),
            "$.id");

    // The colleague is informed rather than asked (Q11), and that is a thing they can go and read.
    mockMvc
        .perform(asThemself(mockMvc, get("/api/notifications"), organisation, grace))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].kind").value("SWAP_REQUESTED"))
        .andExpect(jsonPath("$[0].subject").value(swap))
        .andExpect(jsonPath("$[0].read").value(false));

    mockMvc
        .perform(as(mockMvc, post("/api/swaps/" + swap + "/approval"), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.assignments.length()").value(1))
        .andExpect(jsonPath("$.assignments[0].employee").value(grace))
        .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(roster)));

    // R6: the roster people were working to is superseded, not edited, and still says what it said.
    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUPERSEDED"))
        .andExpect(jsonPath("$.assignments[0].employee").value(ada));

    mockMvc
        .perform(asThemself(mockMvc, get("/api/notifications"), organisation, ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].kind").value("SWAP_APPROVED"));

    // And the person who took it on now sees it as their own work (F5).
    mockMvc
        .perform(
            asThemself(mockMvc, get("/api/me/schedule"), organisation, grace)
                .param("from", "2026-12-07T00:00:00Z")
                .param("to", "2026-12-08T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].shift").value(shift));

    mockMvc
        .perform(
            asThemself(mockMvc, get("/api/me/schedule"), organisation, ada)
                .param("from", "2026-12-07T00:00:00Z")
                .param("to", "2026-12-08T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void GivenASwapIsRejected_WhenTheDecisionIsMade_ThenNoRosterChangesAndTheRequesterIsTold()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada Lovelace");
    String grace = addEmployee(organisation, "Grace Hopper");
    Rostered rostered = publishedFor(organisation, ada);
    String shift = rostered.shift();
    String roster = rostered.roster();
    String swap =
        JsonPath.read(
            created(
                asThemself(mockMvc, post("/api/swaps"), organisation, ada)
                    .content(
                        "{\"roster\":\"%s\",\"shift\":\"%s\",\"requester\":\"%s\",\"counterparty\":\"%s\"}"
                            .formatted(roster, shift, ada, grace))),
            "$.id");

    mockMvc
        .perform(as(mockMvc, post("/api/swaps/" + swap + "/rejection"), organisation, "SCHEDULER"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.assignments[0].employee").value(ada));
    mockMvc
        .perform(asThemself(mockMvc, get("/api/notifications"), organisation, ada))
        .andExpect(jsonPath("$[0].kind").value("SWAP_REJECTED"));
  }

  @Test
  void GivenADecidedSwap_WhenItIsDecidedAgain_ThenItIsAConflict() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada Lovelace");
    String grace = addEmployee(organisation, "Grace Hopper");
    Rostered rostered = publishedFor(organisation, ada);
    String shift = rostered.shift();
    String roster = rostered.roster();
    String swap =
        JsonPath.read(
            created(
                asThemself(mockMvc, post("/api/swaps"), organisation, ada)
                    .content(
                        "{\"roster\":\"%s\",\"shift\":\"%s\",\"requester\":\"%s\",\"counterparty\":\"%s\"}"
                            .formatted(roster, shift, ada, grace))),
            "$.id");
    mockMvc
        .perform(as(mockMvc, post("/api/swaps/" + swap + "/rejection"), organisation, "SCHEDULER"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(as(mockMvc, post("/api/swaps/" + swap + "/approval"), organisation, "SCHEDULER"))
        .andExpect(status().isConflict());
  }

  @Test
  void GivenAnEmployeeRole_WhenApprovingASwap_ThenItIsForbidden() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada Lovelace");
    String grace = addEmployee(organisation, "Grace Hopper");
    Rostered rostered = publishedFor(organisation, ada);
    String shift = rostered.shift();
    String roster = rostered.roster();
    String swap =
        JsonPath.read(
            created(
                asThemself(mockMvc, post("/api/swaps"), organisation, ada)
                    .content(
                        "{\"roster\":\"%s\",\"shift\":\"%s\",\"requester\":\"%s\",\"counterparty\":\"%s\"}"
                            .formatted(roster, shift, ada, grace))),
            "$.id");

    mockMvc
        .perform(asThemself(mockMvc, post("/api/swaps/" + swap + "/approval"), organisation, grace))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenAColleaguesShift_WhenAnEmployeeAsksToSwapIt_ThenItIsForbidden() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada Lovelace");
    String grace = addEmployee(organisation, "Grace Hopper");
    Rostered rostered = publishedFor(organisation, ada);
    String shift = rostered.shift();
    String roster = rostered.roster();

    mockMvc
        .perform(
            asThemself(mockMvc, post("/api/swaps"), organisation, grace)
                .content(
                    "{\"roster\":\"%s\",\"shift\":\"%s\",\"requester\":\"%s\",\"counterparty\":\"%s\"}"
                        .formatted(roster, shift, ada, grace)))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenNoCounterpartyInTheBody_WhenAskingForASwap_ThenItIsABadRequest() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada Lovelace");
    Rostered rostered = publishedFor(organisation, ada);
    String shift = rostered.shift();
    String roster = rostered.roster();

    mockMvc
        .perform(
            asThemself(mockMvc, post("/api/swaps"), organisation, ada)
                .content(
                    "{\"roster\":\"%s\",\"shift\":\"%s\",\"requester\":\"%s\"}"
                        .formatted(roster, shift, ada)))
        .andExpect(status().isBadRequest());
  }

  /** Raises Ada's request to hand her shift to Grace, and returns where its 201 said it lives. */
  private String requestedLocation(String organisation, String ada, String grace) throws Exception {
    Rostered rostered = publishedFor(organisation, ada);
    return mockMvc
        .perform(
            asThemself(mockMvc, post("/api/swaps"), organisation, ada)
                .content(
                    "{\"roster\":\"%s\",\"shift\":\"%s\",\"requester\":\"%s\",\"counterparty\":\"%s\"}"
                        .formatted(rostered.roster(), rostered.shift(), ada, grace)))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getHeader("Location");
  }

  @Test
  void GivenARequestedSwap_WhenTheRequesterFollowsItsLocation_ThenItIsFound() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada Lovelace");
    String grace = addEmployee(organisation, "Grace Hopper");
    String location = requestedLocation(organisation, ada, grace);

    mockMvc
        .perform(asThemself(mockMvc, get(location), organisation, ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requester").value(ada))
        .andExpect(jsonPath("$.counterparty").value(grace))
        .andExpect(jsonPath("$.status").value("REQUESTED"));
  }

  @Test
  void GivenAScheduler_WhenReadingAnySwap_ThenItIsFound() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada Lovelace");
    String grace = addEmployee(organisation, "Grace Hopper");
    String location = requestedLocation(organisation, ada, grace);

    mockMvc
        .perform(as(mockMvc, get(location), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requester").value(ada));
  }

  /**
   * The listing leaves out a swap somebody is not party to, so reading it directly must not put it
   * back. 404 rather than 403, because a 403 would confirm there is something there to see.
   */
  @Test
  void GivenAColleagueNotPartyToTheSwap_WhenReadingIt_ThenThereIsNothingThere() throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation, "Ada Lovelace");
    String grace = addEmployee(organisation, "Grace Hopper");
    String edsger = addEmployee(organisation, "Edsger Dijkstra");
    String location = requestedLocation(organisation, ada, grace);

    mockMvc
        .perform(asThemself(mockMvc, get(location), organisation, edsger))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.detail").value("No such resource."));
  }
}
