package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.as;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.asThemself;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
            as(post("/api/employees"), organisation, "ADMIN")
                .content("{\"name\":\"%s\"}".formatted(name))),
        "$.id");
  }

  private String defineShift(String organisation, String start, String end) throws Exception {
    return JsonPath.read(
        created(
            as(post("/api/shifts"), organisation, "SCHEDULER")
                .content(
                    "{\"start\":\"%s\",\"end\":\"%s\",\"requiredHeadcount\":1}"
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
                as(post("/api/rosters"), organisation, "SCHEDULER")
                    .content(
                        "{\"start\":\"2026-12-07T00:00:00Z\",\"end\":\"2026-12-08T00:00:00Z\"}")),
            "$.id");
    String shift = defineShift(organisation, "2026-12-07T08:00:00Z", "2026-12-07T16:00:00Z");
    mockMvc
        .perform(
            as(post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(shift, employee)))
        .andExpect(status().isOk());
    mockMvc
        .perform(as(post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"))
        .andExpect(status().isOk());
    return new Rostered(roster, shift);
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
                asThemself(post("/api/swaps"), organisation, ada)
                    .content(
                        "{\"roster\":\"%s\",\"shift\":\"%s\",\"requester\":\"%s\",\"counterparty\":\"%s\"}"
                            .formatted(roster, shift, ada, grace))),
            "$.id");

    // The colleague is informed rather than asked (Q11), and that is a thing they can go and read.
    mockMvc
        .perform(asThemself(get("/api/notifications"), organisation, grace))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].kind").value("SWAP_REQUESTED"))
        .andExpect(jsonPath("$[0].subject").value(swap))
        .andExpect(jsonPath("$[0].read").value(false));

    mockMvc
        .perform(as(post("/api/swaps/" + swap + "/approval"), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.assignments.length()").value(1))
        .andExpect(jsonPath("$.assignments[0].employee").value(grace))
        .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(roster)));

    // R6: the roster people were working to is superseded, not edited, and still says what it said.
    mockMvc
        .perform(as(get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUPERSEDED"))
        .andExpect(jsonPath("$.assignments[0].employee").value(ada));

    mockMvc
        .perform(asThemself(get("/api/notifications"), organisation, ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].kind").value("SWAP_APPROVED"));

    // And the person who took it on now sees it as their own work (F5).
    mockMvc
        .perform(
            asThemself(get("/api/me/schedule"), organisation, grace)
                .param("from", "2026-12-07T00:00:00Z")
                .param("to", "2026-12-08T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].shift").value(shift));

    mockMvc
        .perform(
            asThemself(get("/api/me/schedule"), organisation, ada)
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
                asThemself(post("/api/swaps"), organisation, ada)
                    .content(
                        "{\"roster\":\"%s\",\"shift\":\"%s\",\"requester\":\"%s\",\"counterparty\":\"%s\"}"
                            .formatted(roster, shift, ada, grace))),
            "$.id");

    mockMvc
        .perform(as(post("/api/swaps/" + swap + "/rejection"), organisation, "SCHEDULER"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(as(get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.assignments[0].employee").value(ada));
    mockMvc
        .perform(asThemself(get("/api/notifications"), organisation, ada))
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
                asThemself(post("/api/swaps"), organisation, ada)
                    .content(
                        "{\"roster\":\"%s\",\"shift\":\"%s\",\"requester\":\"%s\",\"counterparty\":\"%s\"}"
                            .formatted(roster, shift, ada, grace))),
            "$.id");
    mockMvc
        .perform(as(post("/api/swaps/" + swap + "/rejection"), organisation, "SCHEDULER"))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(as(post("/api/swaps/" + swap + "/approval"), organisation, "SCHEDULER"))
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
                asThemself(post("/api/swaps"), organisation, ada)
                    .content(
                        "{\"roster\":\"%s\",\"shift\":\"%s\",\"requester\":\"%s\",\"counterparty\":\"%s\"}"
                            .formatted(roster, shift, ada, grace))),
            "$.id");

    mockMvc
        .perform(asThemself(post("/api/swaps/" + swap + "/approval"), organisation, grace))
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
            asThemself(post("/api/swaps"), organisation, grace)
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
            asThemself(post("/api/swaps"), organisation, ada)
                .content(
                    "{\"roster\":\"%s\",\"shift\":\"%s\",\"requester\":\"%s\"}"
                        .formatted(roster, shift, ada)))
        .andExpect(status().isBadRequest());
  }
}
