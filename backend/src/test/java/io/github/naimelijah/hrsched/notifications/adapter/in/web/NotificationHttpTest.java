package io.github.naimelijah.hrsched.notifications.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import io.github.naimelijah.hrsched.shared.testsupport.TestTenants;
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
 * Drives the notification API over HTTP.
 *
 * <p>There is deliberately no endpoint that raises a notification, so where a test needs one in an
 * inbox it raises it the way the system does, by asking for a swap. What this covers is the shape
 * of the API and its refusals: whose inbox is read comes from the acting identity, so there is no
 * request that even expresses "somebody else's", and acknowledging a notification addressed to
 * somebody else, in this organisation or another, finds nothing.
 *
 * <p>Every day and name below is invented.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class NotificationHttpTest {

  @Autowired private MockMvc mockMvc;

  private String anOrganisation() throws Exception {
    String body =
        mockMvc
            .perform(
                TestTenants.with(post("/api/organisations"), TestTenants.operatorToken(mockMvc))
                    .content(
                        TestTenants.onboarding("Organisation %s".formatted(UUID.randomUUID()))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  private MockHttpServletRequestBuilder asThemself(
      MockHttpServletRequestBuilder request, String organisation, String employee)
      throws Exception {
    return TestTenants.asThemself(mockMvc, request, organisation, employee);
  }

  @Test
  void GivenNothingHasHappenedToThem_WhenReadingTheirInbox_ThenItIsEmpty() throws Exception {
    mockMvc
        .perform(
            asThemself(get("/api/notifications"), anOrganisation(), UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void GivenNoPersonnelRecordIsClaimed_WhenReadingAnInbox_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(TestTenants.as(mockMvc, get("/api/notifications"), anOrganisation(), "ADMIN"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenTheOperator_WhenReadingAnInbox_ThenItIsForbidden() throws Exception {
    mockMvc
        .perform(TestTenants.as(mockMvc, get("/api/notifications"), anOrganisation(), "OPERATOR"))
        .andExpect(status().isForbidden());
  }

  @Test
  void GivenNoSuchNotification_WhenAcknowledgingIt_ThenItIsNotFound() throws Exception {
    mockMvc
        .perform(
            asThemself(
                post("/api/notifications/" + UUID.randomUUID() + "/read"),
                anOrganisation(),
                UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());
  }

  @Test
  void GivenAnIdentifierThatIsNotAUuid_WhenAcknowledgingIt_ThenItIsABadRequest() throws Exception {
    mockMvc
        .perform(
            asThemself(
                post("/api/notifications/not-a-uuid/read"),
                anOrganisation(),
                UUID.randomUUID().toString()))
        .andExpect(status().isBadRequest());
  }

  // --- #250: acknowledging a notification that really exists, as somebody it is not for ---------

  /** An invented day, rostered only so that a swap can be asked for on it. */
  private static final String DAY_START = "2026-12-14T00:00:00Z";

  private static final String DAY_END = "2026-12-15T00:00:00Z";

  /** Eight invented hours on it. */
  private static final String SHIFT_START = "2026-12-14T08:00:00Z";

  private static final String SHIFT_END = "2026-12-14T16:00:00Z";

  private MockHttpServletRequestBuilder as(
      MockHttpServletRequestBuilder request, String organisation, String role) throws Exception {
    return TestTenants.as(mockMvc, request, organisation, role);
  }

  private String answered(MockHttpServletRequestBuilder request, int status) throws Exception {
    return mockMvc
        .perform(request)
        .andExpect(status().is(status))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String addEmployee(String organisation, String name) throws Exception {
    return JsonPath.read(
        answered(
            as(post("/api/employees"), organisation, "ADMIN")
                .content("{\"name\":\"%s\"}".formatted(name)),
            201),
        "$.id");
  }

  /**
   * Ada's request to hand her shift to Grace, which tells Grace (Q11). Built the way {@code
   * SwapHttpTest} builds it, through the real routes, because nothing else puts anything in an
   * inbox and raising a notification is deliberately not an endpoint.
   *
   * @return the identity of the notification in Grace's inbox
   */
  private String notificationForGrace(String organisation, String ada, String grace)
      throws Exception {
    String generation =
        JsonPath.read(
            answered(
                as(post("/api/rosters/generations"), organisation, "SCHEDULER")
                    .content("{\"start\":\"%s\",\"end\":\"%s\"}".formatted(DAY_START, DAY_END)),
                202),
            "$.id");
    String roster =
        JsonPath.read(
            answered(
                as(get("/api/rosters/generations/" + generation), organisation, "SCHEDULER"), 200),
            "$.roster");
    String shift =
        JsonPath.read(
            answered(
                as(post("/api/shifts"), organisation, "SCHEDULER")
                    .content(
                        "{\"start\":\"%s\",\"end\":\"%s\",\"zone\":\"UTC\",\"requiredHeadcount\":1}"
                            .formatted(SHIFT_START, SHIFT_END)),
                201),
            "$.id");
    answered(
        as(post("/api/rosters/" + roster + "/assignments"), organisation, "SCHEDULER")
            .content("{\"shift\":\"%s\",\"employee\":\"%s\"}".formatted(shift, ada)),
        200);
    answered(as(post("/api/rosters/" + roster + "/publication"), organisation, "SCHEDULER"), 200);
    answered(
        asThemself(post("/api/swaps"), organisation, ada)
            .content(
                "{\"roster\":\"%s\",\"shift\":\"%s\",\"requester\":\"%s\",\"counterparty\":\"%s\"}"
                    .formatted(roster, shift, ada, grace)),
        201);
    return JsonPath.read(
        answered(asThemself(get("/api/notifications"), organisation, grace), 200), "$[0].id");
  }

  private void assertStillUnreadInGracesInbox(
      String organisation, String grace, String notification) throws Exception {
    mockMvc
        .perform(asThemself(get("/api/notifications"), organisation, grace))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(notification))
        .andExpect(jsonPath("$[0].read").value(false));
  }

  /**
   * The acknowledgement that succeeds: Grace's own notification is answered 204 and then reads as
   * read. Acknowledging it again, as a second open tab does, is a 204 too rather than an error.
   */
  @Test
  void GivenTheirOwnNotification_WhenAcknowledgedTwice_ThenEachIsNoContentAndItReadsAsRead()
      throws Exception {
    String organisation = anOrganisation();
    String ada = addEmployee(organisation, "Ada");
    String grace = addEmployee(organisation, "Grace");
    String gracesNotification = notificationForGrace(organisation, ada, grace);

    mockMvc
        .perform(
            asThemself(
                post("/api/notifications/" + gracesNotification + "/read"), organisation, grace))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(asThemself(get("/api/notifications"), organisation, grace))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(gracesNotification))
        .andExpect(jsonPath("$[0].read").value(true));

    mockMvc
        .perform(
            asThemself(
                post("/api/notifications/" + gracesNotification + "/read"), organisation, grace))
        .andExpect(status().isNoContent());
  }

  /**
   * Ada asked for the swap, so she knows the notification exists; it is still Grace's to
   * acknowledge. A 404, the answer for one that never existed, rather than a 403 that would confirm
   * it does.
   */
  @Test
  void GivenAColleaguesNotification_WhenAcknowledgingIt_ThenItIsNotFoundAndStaysUnread()
      throws Exception {
    String organisation = anOrganisation();
    String ada = addEmployee(organisation, "Ada");
    String grace = addEmployee(organisation, "Grace");
    String gracesNotification = notificationForGrace(organisation, ada, grace);

    mockMvc
        .perform(
            asThemself(
                post("/api/notifications/" + gracesNotification + "/read"), organisation, ada))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    assertStillUnreadInGracesInbox(organisation, grace, gracesNotification);
  }

  @Test
  void
      GivenAnotherOrganisationsNotification_WhenOurMemberAcknowledgesIt_ThenItIsNotFoundAndStaysUnread()
          throws Exception {
    String theirs = anOrganisation();
    String ada = addEmployee(theirs, "Ada");
    String grace = addEmployee(theirs, "Grace");
    String theirNotification = notificationForGrace(theirs, ada, grace);
    String ours = anOrganisation();
    String ourMember = addEmployee(ours, "Edsger");

    mockMvc
        .perform(
            asThemself(post("/api/notifications/" + theirNotification + "/read"), ours, ourMember))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    assertStillUnreadInGracesInbox(theirs, grace, theirNotification);
  }
}
