package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.as;
import static io.github.naimelijah.hrsched.workforce.adapter.in.web.WorkforceHttpRequests.asThemself;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Values the system cannot store or represent are the caller's mistake, answered 400 with a problem
 * detail, and nothing of them is stored.
 *
 * <p>The limits are technical, not rules of the business: an instant must lie within what the store
 * holds, a span must not collapse to nothing once stored at the store's precision, and a count of
 * minutes must convert to a duration without overflowing. Before these were checked, each reached
 * the adapter or {@code Duration} unchecked and answered 500 with an ERROR log — a caller's typo
 * reported as our defect. Instants just inside each limit are stored as sent, so the limits are
 * pinned from both sides.
 *
 * <p>No refusal repeats the value sent (ADR-024): a span says when somebody cannot work, and
 * contracted hours say things about a household.
 *
 * <p>EVERY INSTANT AND FIGURE BELOW IS INVENTED. The out-of-range instants are chosen only for
 * lying past a technical limit — what {@code OffsetDateTime} can hold, what PostgreSQL's {@code
 * timestamptz} can, and what pgjdbc writes faithfully — and the ordinary ones only for being
 * ordinary.
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

  /**
   * Invented. Inside PostgreSQL's range, which begins at 4714-11-24 BC, but before 4713-01-01 BC,
   * where pgjdbc stops writing an instant as itself and writes {@code -infinity} instead.
   */
  private static final String BEFORE_THE_EARLIEST_STORABLE = "-4713-11-24T00:00:00Z";

  /** Invented, though the limit is not: the first instant pgjdbc writes as itself. */
  private static final String EARLIEST_STORABLE = "-4712-01-01T00:00:00Z";

  /** Invented, though the limit is not: the last instant PostgreSQL's timestamptz holds. */
  private static final String LATEST_STORABLE = "+294276-12-31T23:59:59.999999Z";

  /**
   * Invented. Half a microsecond after {@link #LATEST_STORABLE}, which pgjdbc's half-up rounding
   * would carry past the edge.
   */
  private static final String ROUNDING_PAST_THE_LATEST = "+294276-12-31T23:59:59.9999995Z";

  /** Invented. An ordinary instant to end a span whose start is the one under test. */
  private static final String ORDINARY_END = "2026-11-16T10:00:00Z";

  /** Invented. An ordinary instant to start a span whose end is the one under test. */
  private static final String ORDINARY_START = "2026-11-16T08:00:00Z";

  /** Invented. A whole number of minutes for which {@code Duration.ofMinutes} overflows upwards. */
  private static final long OVERFLOWING_MINUTES = Long.MAX_VALUE;

  /**
   * Invented. A whole number of minutes for which {@code Duration.ofMinutes} overflows downwards.
   */
  private static final long UNDERFLOWING_MINUTES = Long.MIN_VALUE;

  /**
   * What would give away an instant sent in this class if a detail repeated it in any rendering:
   * its out-of-range year, or its sub-microsecond digits. Every refusal is checked against all of
   * them, as well as against the instants it was itself sent.
   */
  private static final List<String> DISTINCTIVE_PARTS_SENT =
      List.of("300000", "1000000000", ".0000001", ".0000006", "-4713", "294276", ".9999995");

  /** The leading digits both {@link Long#MAX_VALUE} and {@link Long#MIN_VALUE} share. */
  private static final String DISTINCTIVE_PART_OF_THE_MINUTES = "922337203685477580";

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

  private static void isRefusedRepeatingNothingSent(ResultActions response, String... sent)
      throws Exception {
    response
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.title").value("Invalid request"))
        .andExpect(jsonPath("$.detail").isNotEmpty());
    for (String value : sent) {
      response.andExpect(jsonPath("$.detail").value(not(containsString(value))));
    }
    for (String part : DISTINCTIVE_PARTS_SENT) {
      response.andExpect(jsonPath("$.detail").value(not(containsString(part))));
    }
  }

  private void declaringOwnUnavailabilityIsRefused(String start, String end) throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation);

    isRefusedRepeatingNothingSent(
        mockMvc.perform(
            asThemself(
                    mockMvc, post("/api/employees/" + ada + "/unavailability"), organisation, ada)
                .content("{\"start\":\"%s\",\"end\":\"%s\"}".formatted(start, end))),
        start,
        end);

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

    isRefusedRepeatingNothingSent(
        mockMvc.perform(
            asThemself(mockMvc, post("/api/employees/" + ada + "/preferences"), organisation, ada)
                .content(
                    "{\"start\":\"%s\",\"end\":\"%s\",\"strength\":\"PREFERS\"}"
                        .formatted(start, end))),
        start,
        end);

    mockMvc
        .perform(
            asThemself(mockMvc, get("/api/employees/" + ada + "/preferences"), organisation, ada))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  /** Declares the span as the Employee themself, then reads back exactly the instants given. */
  private void declaringOwnUnavailabilityStores(
      String start, String end, String storedStart, String storedEnd) throws Exception {
    String organisation = anOrganisation(mockMvc);
    String ada = addEmployee(organisation);

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
        .andExpect(jsonPath("$[0].start").value(storedStart))
        .andExpect(jsonPath("$[0].end").value(storedEnd));
  }

  private void settingAContractIsRefusedAndTheOldOneKept(long minutes) throws Exception {
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
                .content("{\"weeklyContractedMinutes\":%d}".formatted(minutes)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.title").value("Invalid request"))
        .andExpect(jsonPath("$.detail").isNotEmpty())
        .andExpect(jsonPath("$.detail").value(not(containsString(String.valueOf(minutes)))))
        .andExpect(
            jsonPath("$.detail").value(not(containsString(DISTINCTIVE_PART_OF_THE_MINUTES))));

    mockMvc
        .perform(as(mockMvc, get("/api/employees/" + ada), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.weeklyContractedMinutes").value(ordinaryMinutes));
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

  // --- the lower bound ------------------------------------------------------------------------

  @Test
  void GivenAStartBeforeTheEarliestStorable_WhenDeclaringOwnUnavailability_ThenRefusedUnstored()
      throws Exception {
    declaringOwnUnavailabilityIsRefused(BEFORE_THE_EARLIEST_STORABLE, ORDINARY_END);
  }

  @Test
  void GivenAStartBeforeTheEarliestStorable_WhenStatingOwnPreference_ThenRefusedUnstored()
      throws Exception {
    statingOwnPreferenceIsRefused(BEFORE_THE_EARLIEST_STORABLE, ORDINARY_END);
  }

  @Test
  void GivenAStartAtExactlyTheEarliestStorable_WhenDeclaringOwnUnavailability_ThenStoredAsSent()
      throws Exception {
    declaringOwnUnavailabilityStores(
        EARLIEST_STORABLE, ORDINARY_END, EARLIEST_STORABLE, ORDINARY_END);
  }

  // --- the upper edge -------------------------------------------------------------------------

  @Test
  void GivenAnEndAtExactlyTheLatestStorable_WhenDeclaringOwnUnavailability_ThenStoredAsSent()
      throws Exception {
    declaringOwnUnavailabilityStores(
        ORDINARY_START, LATEST_STORABLE, ORDINARY_START, LATEST_STORABLE);
  }

  @Test
  void GivenAnEndThatRoundsPastTheLatestStorable_WhenDeclaringOwnUnavailability_ThenRefused()
      throws Exception {
    declaringOwnUnavailabilityIsRefused(ORDINARY_START, ROUNDING_PAST_THE_LATEST);
  }

  // --- the rounding direction: half-up to the microsecond, as pgjdbc stores it ----------------

  /**
   * 0.6 microseconds rounds up to 1 and 1.4 rounds down to 1, so the span is empty once stored. A
   * truncating check would see 0 and 1, and let it through to the table's CHECK.
   */
  @Test
  void GivenEndsRoundingToTheSameMicrosecond_WhenDeclaringOwnUnavailability_ThenRefusedUnstored()
      throws Exception {
    declaringOwnUnavailabilityIsRefused(
        "2026-11-16T09:00:00.0000006Z", "2026-11-16T09:00:00.0000014Z");
  }

  /**
   * 0.4 microseconds rounds down to 0 and 0.6 up to 1, so the span survives storing as one
   * microsecond. A truncating check would see 0 and 0, and refuse a span the store would keep.
   */
  @Test
  void GivenEndsRoundingToAdjacentMicroseconds_WhenDeclaringOwnUnavailability_ThenStoredRounded()
      throws Exception {
    declaringOwnUnavailabilityStores(
        "2026-11-16T09:00:00.0000004Z",
        "2026-11-16T09:00:00.0000006Z",
        "2026-11-16T09:00:00Z",
        "2026-11-16T09:00:00.000001Z");
  }

  // --- contracted minutes ---------------------------------------------------------------------

  /**
   * The contract already held is read back unchanged, which shows the refused one replaced nothing.
   */
  @Test
  void GivenMinutesThatOverflowADuration_WhenAnAdminSetsAContract_ThenRefusedAndTheOldOneKept()
      throws Exception {
    settingAContractIsRefusedAndTheOldOneKept(OVERFLOWING_MINUTES);
  }

  @Test
  void GivenMinutesThatUnderflowADuration_WhenAnAdminSetsAContract_ThenRefusedAndTheOldOneKept()
      throws Exception {
    settingAContractIsRefusedAndTheOldOneKept(UNDERFLOWING_MINUTES);
  }

  /**
   * The precision limit is the store's microsecond and no coarser: a span exactly one microsecond
   * long is ordinary, and is kept to the instant.
   */
  @Test
  void GivenEndsOneMicrosecondApart_WhenDeclaringOwnUnavailability_ThenItIsStoredAsSent()
      throws Exception {
    // Invented: ordinary instants, one microsecond apart.
    String start = "2026-11-17T09:00:00.000001Z";
    String end = "2026-11-17T09:00:00.000002Z";

    declaringOwnUnavailabilityStores(start, end, start, end);
  }
}
