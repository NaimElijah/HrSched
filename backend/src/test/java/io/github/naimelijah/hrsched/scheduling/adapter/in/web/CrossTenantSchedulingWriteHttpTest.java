package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.as;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.asThemself;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.generateDraft;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Every scheduling route that changes something, asked by one organisation about another's roster
 * or swap.
 *
 * <p>Our member uses our own token and names their identity. Each answer is a 404, never a 403,
 * because a 403 would confirm the thing exists; and their row reads back unchanged as their own
 * member sees it. A failure of any test here is a cross-tenant write, a security incident rather
 * than a bug, which is why each route has its own test rather than a row in a table.
 *
 * <p>Where the route also names a shift or a person, it names whichever one leaves only the
 * organisation's scoping to stop the write. Assigning to their draft names our own shift and our
 * own person, so nothing but the roster lookup can refuse; raising a swap on their roster names
 * their people as a Scheduler of ours may, since an Employee of ours is not on their roster at all
 * and would be refused for that instead.
 *
 * <p>Every day below is invented, and every name is a stand-in.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class CrossTenantSchedulingWriteHttpTest {

  /** An invented day both organisations roster. */
  private static final String DAY_START = "2026-12-21T00:00:00Z";

  private static final String DAY_END = "2026-12-22T00:00:00Z";

  /** Eight invented hours on it. */
  private static final String SHIFT_START = "2026-12-21T08:00:00Z";

  private static final String SHIFT_END = "2026-12-21T16:00:00Z";

  @Autowired private MockMvc mockMvc;

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

  private String defineShift(String organisation) throws Exception {
    return JsonPath.read(
        created(
            as(mockMvc, post("/api/shifts"), organisation, "SCHEDULER")
                .content(
                    "{\"start\":\"%s\",\"end\":\"%s\",\"zone\":\"UTC\",\"requiredHeadcount\":1}"
                        .formatted(SHIFT_START, SHIFT_END))),
        "$.id");
  }

  /** A draft with nothing on it: generated before the organisation has any shift to cover. */
  private String emptyDraft(String organisation) throws Exception {
    return generateDraft(mockMvc, organisation, DAY_START, DAY_END);
  }

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

  /** Their draft carrying one person of theirs on one shift of theirs. */
  private record Staffed(String roster, String shift, String employee) {}

  private Staffed staffedDraft(String organisation) throws Exception {
    String roster = emptyDraft(organisation);
    String shift = defineShift(organisation);
    String grace = addEmployee(organisation, "Grace");
    assign(organisation, roster, shift, grace);
    return new Staffed(roster, shift, grace);
  }

  /** Their published roster carrying Ada, and the colleague she could hand her shift to. */
  private record Published(String roster, String shift, String ada, String grace) {}

  private Published published(String organisation) throws Exception {
    String roster = emptyDraft(organisation);
    String shift = defineShift(organisation);
    String ada = addEmployee(organisation, "Ada");
    String grace = addEmployee(organisation, "Grace");
    assign(organisation, roster, shift, ada);
    publish(organisation, roster);
    return new Published(roster, shift, ada, grace);
  }

  private static String swapBody(String roster, String shift, String requester, String other) {
    return "{\"roster\":\"%s\",\"shift\":\"%s\",\"requester\":\"%s\",\"counterparty\":\"%s\"}"
        .formatted(roster, shift, requester, other);
  }

  /** Ada asks, as herself, to hand her shift to Grace; returns the request's identity. */
  private String pendingSwap(String organisation, Published rostered) throws Exception {
    return JsonPath.read(
        created(
            asThemself(mockMvc, post("/api/swaps"), organisation, rostered.ada())
                .content(
                    swapBody(
                        rostered.roster(), rostered.shift(), rostered.ada(), rostered.grace()))),
        "$.id");
  }

  private static void assertNotFound(ResultActions answer) throws Exception {
    answer
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  private ResultActions theirRoster(String organisation, String roster) throws Exception {
    return mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk());
  }

  private void assertTheirSwapIsStillPending(String organisation, String swap) throws Exception {
    mockMvc
        .perform(as(mockMvc, get("/api/swaps/" + swap), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REQUESTED"));
  }

  @Test
  void GivenAnotherOrganisationsDraft_WhenOurSchedulerAssignsToIt_ThenItIsNotFoundAndItIsUnchanged()
      throws Exception {
    String ours = anOrganisation(mockMvc);
    String theirs = anOrganisation(mockMvc);
    String theirDraft = emptyDraft(theirs);
    String ourShift = defineShift(ours);
    String ourAda = addEmployee(ours, "Ada");

    assertNotFound(
        mockMvc.perform(
            as(mockMvc, post("/api/rosters/" + theirDraft + "/assignments"), ours, "SCHEDULER")
                .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(ourShift, ourAda))));

    theirRoster(theirs, theirDraft)
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.assignments.length()").value(0));
  }

  @Test
  void
      GivenAnotherOrganisationsDraft_WhenOurSchedulerUnassignsFromIt_ThenItIsNotFoundAndItIsUnchanged()
          throws Exception {
    String ours = anOrganisation(mockMvc);
    String theirs = anOrganisation(mockMvc);
    Staffed theirDraft = staffedDraft(theirs);

    assertNotFound(
        mockMvc.perform(
            as(
                mockMvc,
                delete(
                    "/api/rosters/%s/assignments/%s/%s"
                        .formatted(theirDraft.roster(), theirDraft.shift(), theirDraft.employee())),
                ours,
                "SCHEDULER")));

    theirRoster(theirs, theirDraft.roster())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.assignments.length()").value(1))
        .andExpect(jsonPath("$.assignments[0].employee").value(theirDraft.employee()));
  }

  @Test
  void
      GivenAnotherOrganisationsDraft_WhenOurSchedulerPublishesIt_ThenItIsNotFoundAndItIsStillADraft()
          throws Exception {
    String ours = anOrganisation(mockMvc);
    String theirs = anOrganisation(mockMvc);
    String theirDraft = emptyDraft(theirs);

    assertNotFound(
        mockMvc.perform(
            as(mockMvc, post("/api/rosters/" + theirDraft + "/publication"), ours, "SCHEDULER")));

    theirRoster(theirs, theirDraft).andExpect(jsonPath("$.status").value("DRAFT"));
  }

  @Test
  void
      GivenAnotherOrganisationsPublishedRoster_WhenOurSchedulerAsksToSwapOnIt_ThenItIsNotFoundAndNothingIsAsked()
          throws Exception {
    String ours = anOrganisation(mockMvc);
    String theirs = anOrganisation(mockMvc);
    Published rostered = published(theirs);

    assertNotFound(
        mockMvc.perform(
            as(mockMvc, post("/api/swaps"), ours, "SCHEDULER")
                .content(
                    swapBody(
                        rostered.roster(), rostered.shift(), rostered.ada(), rostered.grace()))));

    mockMvc
        .perform(as(mockMvc, get("/api/swaps"), theirs, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    theirRoster(theirs, rostered.roster())
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.assignments[0].employee").value(rostered.ada()));
  }

  @Test
  void GivenAnotherOrganisationsSwap_WhenOurSchedulerReadsIt_ThenItIsNotFound() throws Exception {
    String ours = anOrganisation(mockMvc);
    String theirs = anOrganisation(mockMvc);
    String theirSwap = pendingSwap(theirs, published(theirs));

    assertNotFound(mockMvc.perform(as(mockMvc, get("/api/swaps/" + theirSwap), ours, "SCHEDULER")));

    assertTheirSwapIsStillPending(theirs, theirSwap);
  }

  @Test
  void GivenAnotherOrganisationsSwap_WhenOurSchedulerApprovesIt_ThenItIsNotFoundAndNothingChanges()
      throws Exception {
    String ours = anOrganisation(mockMvc);
    String theirs = anOrganisation(mockMvc);
    Published rostered = published(theirs);
    String theirSwap = pendingSwap(theirs, rostered);

    assertNotFound(
        mockMvc.perform(
            as(mockMvc, post("/api/swaps/" + theirSwap + "/approval"), ours, "SCHEDULER")));

    assertTheirSwapIsStillPending(theirs, theirSwap);
    theirRoster(theirs, rostered.roster())
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.assignments[0].employee").value(rostered.ada()));
  }

  @Test
  void GivenAnotherOrganisationsSwap_WhenOurSchedulerRejectsIt_ThenItIsNotFoundAndItIsStillPending()
      throws Exception {
    String ours = anOrganisation(mockMvc);
    String theirs = anOrganisation(mockMvc);
    String theirSwap = pendingSwap(theirs, published(theirs));

    assertNotFound(
        mockMvc.perform(
            as(mockMvc, post("/api/swaps/" + theirSwap + "/rejection"), ours, "SCHEDULER")));

    assertTheirSwapIsStillPending(theirs, theirSwap);
  }
}
