package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.as;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.asThemself;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.MockMvc;

/**
 * Values the system cannot store or represent are the caller's mistake, answered 400 with a problem
 * detail, and nothing of them is stored.
 *
 * <p>The limits are technical, not rules of the business: an instant must lie within what the store
 * holds, a span must not collapse to nothing once stored at the store's precision, and a count of
 * minutes must convert to a duration without overflowing. Before these were checked, each reached
 * the adapter or {@code Duration} unchecked and answered 500 with an ERROR log — a caller's typo
 * reported as our defect.
 *
 * <p>EVERY INSTANT AND FIGURE BELOW IS INVENTED. The out-of-range instants are chosen only for
 * lying past a technical limit — what {@code OffsetDateTime} can hold, and what PostgreSQL's {@code
 * timestamptz} can — and the ordinary ones only for being ordinary.
 *
 * <p>Annotated identically to {@code EmployeeHttpTest}, so it shares the suite's Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class UnrepresentableValueHttpTest {

  /**
   * Invented. A valid {@code Instant} — under {@code Instant.MAX} — whose year is past what {@code
   * OffsetDateTime} holds, so storing it at UTC cannot even be expressed.
   */
  private static final String PAST_A_DATE_TIME = "+1000000000-01-01T00:00:00Z";

  /** Invented. A day after {@link #PAST_A_DATE_TIME}, so the span is otherwise well formed. */
  private static final String PAST_A_DATE_TIME_END = "+1000000000-01-02T00:00:00Z";

  /** Invented. Representable in Java, but past the last year PostgreSQL's timestamptz holds. */
  private static final String PAST_THE_STORE = "+300000-01-01T00:00:00Z";

  /** Invented. A day after {@link #PAST_THE_STORE}. */
  private static final String PAST_THE_STORE_END = "+300000-01-02T00:00:00Z";

  /**
   * Invented. Two ends a hundred nanoseconds apart: a span in Java, but the same microsecond once
   * stored, where the table refuses an end that is not after its start.
   */
  private static final String SUB_MICROSECOND_START = "2026-11-16T09:00:00.000000100Z";

  /** Invented. See {@link #SUB_MICROSECOND_START}. */
  private static final String SUB_MICROSECOND_END = "2026-11-16T09:00:00.000000200Z";

  /** Invented. A whole number of minutes for which {@code Duration.ofMinutes} overflows. */
  private static final long OVERFLOWING_MINUTES = Long.MAX_VALUE;

  @Autowired private MockMvc mockMvc;

  private String addEmployee(String organisation) throws Exception {
    String body =
        mockMvc
            .perform(
                as(mockMvc, post("/api/employees"), organisation, "ADMIN")
                    .content("{\"name\":\"Ada\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.id");
  }

  private void declaringOwnUnavailabilityIsRefused(String start, String end) throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation);

    mockMvc
        .perform(
            asThemself(
                    mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, ada)
                .content("{\"start\":\"%s\",\"end\":\"%s\"}".formatted(start, end)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.detail").isNotEmpty());

    mockMvc
        .perform(
            asThemself(
                mockMvc, get("/api/employees/" + ada + "/unavailability"), organisation, ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  private void statingOwnPreferenceIsRefused(String start, String end) throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation);

    mockMvc
        .perform(
            asThemself(mockMvc, post("/api/employees/" + ada + "/preferences"), organisation, ada)
                .content(
                    "{\"start\":\"%s\",\"end\":\"%s\",\"strength\":\"PREFERS\"}"
                        .formatted(start, end)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.detail").isNotEmpty());

    mockMvc
        .perform(
            asThemself(mockMvc, get("/api/employees/" + ada + "/preferences"), organisation, ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void GivenAnInstantPastWhatADateTimeHolds_WhenDeclaringOwnUnavailability_ThenRefusedUnstored()
      throws Exception {
    declaringOwnUnavailabilityIsRefused(PAST_A_DATE_TIME, PAST_A_DATE_TIME_END);
  }

  @Test
  void GivenAnInstantPastWhatTheStoreHolds_WhenDeclaringOwnUnavailability_ThenRefusedUnstored()
      throws Exception {
    declaringOwnUnavailabilityIsRefused(PAST_THE_STORE, PAST_THE_STORE_END);
  }

  @Test
  void GivenEndsDifferingBelowAMicrosecond_WhenDeclaringOwnUnavailability_ThenRefusedUnstored()
      throws Exception {
    declaringOwnUnavailabilityIsRefused(SUB_MICROSECOND_START, SUB_MICROSECOND_END);
  }

  @Test
  void GivenAnInstantPastWhatADateTimeHolds_WhenStatingOwnPreference_ThenRefusedUnstored()
      throws Exception {
    statingOwnPreferenceIsRefused(PAST_A_DATE_TIME, PAST_A_DATE_TIME_END);
  }

  @Test
  void GivenAnInstantPastWhatTheStoreHolds_WhenStatingOwnPreference_ThenRefusedUnstored()
      throws Exception {
    statingOwnPreferenceIsRefused(PAST_THE_STORE, PAST_THE_STORE_END);
  }

  @Test
  void GivenEndsDifferingBelowAMicrosecond_WhenStatingOwnPreference_ThenRefusedUnstored()
      throws Exception {
    statingOwnPreferenceIsRefused(SUB_MICROSECOND_START, SUB_MICROSECOND_END);
  }

  /**
   * The contract already held is read back unchanged, which shows the refused one replaced nothing.
   */
  @Test
  void GivenMinutesThatOverflowADuration_WhenAnAdminSetsAContract_ThenRefusedAndTheOldOneKept()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation);
    // Invented: contracted hours are a term of an individual agreement, never a statutory figure.
    long ordinaryMinutes = 1500;
    mockMvc
        .perform(
            as(mockMvc, put("/api/employees/" + ada + "/contract"), organisation, "ADMIN")
                .content("{\"weeklyContractedMinutes\":%d}".formatted(ordinaryMinutes)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            as(mockMvc, put("/api/employees/" + ada + "/contract"), organisation, "ADMIN")
                .content("{\"weeklyContractedMinutes\":%d}".formatted(OVERFLOWING_MINUTES)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.detail").isNotEmpty());

    mockMvc
        .perform(as(mockMvc, get("/api/employees/" + ada), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.weeklyContractedMinutes").value(ordinaryMinutes));
  }

  /**
   * The precision limit is the store's microsecond and no coarser: a span exactly one microsecond
   * long is ordinary, and is kept to the instant.
   */
  @Test
  void GivenEndsOneMicrosecondApart_WhenDeclaringOwnUnavailability_ThenItIsStoredAsSent()
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation);
    // Invented: ordinary instants, one microsecond apart.
    String start = "2026-11-17T09:00:00.000001Z";
    String end = "2026-11-17T09:00:00.000002Z";

    mockMvc
        .perform(
            asThemself(
                    mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, ada)
                .content("{\"start\":\"%s\",\"end\":\"%s\"}".formatted(start, end)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            asThemself(
                mockMvc, get("/api/employees/" + ada + "/unavailability"), organisation, ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].start").value(start))
        .andExpect(jsonPath("$[0].end").value(end));
  }
}
