package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.as;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.generateDraft;
import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * Instants a caller can send but the store cannot hold are the caller's mistake, answered 400.
 *
 * <p>Jackson reads any {@code Instant} the JDK can represent. Some of those reach no row: past what
 * {@code OffsetDateTime} holds the conversion to a bind value fails, past what PostgreSQL's {@code
 * timestamptz} holds the database refuses it, and a span whose ends fall inside one microsecond is
 * stored with no length at all, which the schema refuses. Each was a 500, blaming the server for a
 * value the caller chose; and before what {@code timestamptz} holds the driver stores {@code
 * -infinity} instead, so the caller is told it worked. Each must instead be refused as invalid
 * input, with a detail that does not repeat what was sent (ADR-024), and nothing stored. The bounds
 * of a listing are the caller's to choose too, so one the store cannot compare with is refused the
 * same way.
 *
 * <p>What can be stored must still be taken, sub-microsecond precision included: a span sent with
 * nanoseconds is stored to the microsecond, rounded half-up as the driver sends it, as ADR-040
 * records, and that is not a refusal. Each limit is probed at the limit and a microsecond past it.
 *
 * <p>Every date below is invented. The far ones are chosen only to lie at or beyond a limit of the
 * JDK, of PostgreSQL or of its driver, and say nothing about any rule.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class UnstorableInstantHttpTest {

  /** {@code Instant.MAX}: the JDK reads it, and no {@code OffsetDateTime} can hold it. */
  private static final String BEYOND_A_DATE_TIME = "+1000000000-12-31T23:59:59.999999999Z";

  /**
   * Inside what {@code OffsetDateTime} holds and past what {@code timestamptz} does, whose latest
   * year is 294276.
   */
  private static final String BEYOND_THE_DATABASE = "+300000-01-01T00:00:00Z";

  /**
   * Before {@code timestamptz}'s earliest, 24 November 4714 BC, and well inside what the JDK holds.
   * Nothing refuses this one: the driver sends {@code -infinity} for it, so it was stored as an
   * instant nobody sent, and answered as created.
   */
  private static final String BEFORE_THE_DATABASE = "-5000-01-01T00:00:00Z";

  /** An invented ordinary day, on which every span below starts or is looked for. */
  private static final String DAY_START = "2026-12-01T00:00:00Z";

  private static final String DAY_END = "2026-12-02T00:00:00Z";

  /** Eight invented hours on that day. */
  private static final String SHIFT_START = "2026-12-01T08:00:00Z";

  private static final String SHIFT_END = "2026-12-01T16:00:00Z";

  /**
   * Two instants a few hundred nanoseconds apart, inside one microsecond: distinct as sent, equal
   * once stored, whether the driver rounds or truncates.
   */
  private static final String WITHIN_A_MICROSECOND_START = "2026-12-01T08:00:00.000000100Z";

  private static final String WITHIN_A_MICROSECOND_END = "2026-12-01T08:00:00.000000400Z";

  /**
   * The earliest instant stored as sent: 1 January 4713 BC. pgjdbc writes anything earlier as
   * {@code -infinity}, though PostgreSQL itself holds instants back to 24 November 4714 BC.
   */
  private static final String EARLIEST_STORABLE = "-4712-01-01T00:00:00Z";

  /** One microsecond, the precision stored, before the earliest storable instant. */
  private static final String JUST_BEFORE_THE_EARLIEST = "-4713-12-31T23:59:59.999999Z";

  /** Eight invented hours after the earliest storable instant, ending a shift starting there. */
  private static final String EIGHT_HOURS_AFTER_THE_EARLIEST = "-4712-01-01T08:00:00Z";

  /** The last instant {@code timestamptz} holds, at its microsecond precision. */
  private static final String LATEST_STORABLE = "+294276-12-31T23:59:59.999999Z";

  /** One microsecond past the last storable instant. */
  private static final String JUST_PAST_THE_LATEST = "+294277-01-01T00:00:00Z";

  /** Eight invented hours before the latest storable instant, starting a shift ending there. */
  private static final String EIGHT_HOURS_BEFORE_THE_LATEST = "+294276-12-31T16:00:00Z";

  /**
   * Two instants in one second, 0.6 µs and 1.4 µs past it. Rounded half-up, as pgjdbc sends them,
   * both are stored at 1 µs, so the stored span would have no length; truncated, they would be 0 µs
   * and 1 µs, and only the database's CHECK would notice.
   */
  private static final String ROUNDING_TOGETHER_START = "2026-12-01T08:00:00.000000600Z";

  private static final String ROUNDING_TOGETHER_END = "2026-12-01T08:00:00.000001400Z";

  /**
   * Two instants in one second, 0.4 µs and 0.6 µs past it. Rounded half-up they are stored 1 µs
   * apart, at 0 µs and 1 µs; truncated, both would be 0 µs.
   */
  private static final String ROUNDING_APART_START = "2026-12-01T08:00:00.000000400Z";

  private static final String ROUNDING_APART_END = "2026-12-01T08:00:00.000000600Z";

  /** The first, as stored at the microsecond and read back: the second itself. */
  private static final String ROUNDING_APART_START_AS_STORED = "2026-12-01T08:00:00Z";

  /** The second, as stored at the microsecond and read back. */
  private static final String ROUNDING_APART_END_AS_STORED = "2026-12-01T08:00:00.000001Z";

  /** An invented ordinary year, the first bound of every listing whose last bound is too far. */
  private static final String AN_ORDINARY_FROM = "2026-01-01T00:00:00Z";

  @Autowired private MockMvc mockMvc;

  private static String shiftBody(String start, String end) {
    return "{\"start\":\"%s\",\"end\":\"%s\",\"zone\":\"UTC\",\"requiredHeadcount\":1}"
        .formatted(start, end);
  }

  private static String periodBody(String start, String end) {
    return "{\"start\":\"%s\",\"end\":\"%s\"}".formatted(start, end);
  }

  /**
   * Defines the shift, expects it refused as invalid input in a problem detail, and returns that
   * detail's body.
   */
  private String refusedShift(String organisation, String start, String end) throws Exception {
    return mockMvc
        .perform(
            as(mockMvc, post("/api/shifts"), organisation, "SCHEDULER")
                .content(shiftBody(start, end)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").isNotEmpty())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** As {@link #refusedShift}, for starting a generation over the span. */
  private String refusedGeneration(String organisation, String start, String end) throws Exception {
    return mockMvc
        .perform(
            as(mockMvc, post("/api/rosters/generations"), organisation, "SCHEDULER")
                .content(periodBody(start, end)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").isNotEmpty())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** Every shift of the organisation overlapping the invented day; a refused one must not be. */
  private void assertNoShiftOnTheDay(String organisation) throws Exception {
    mockMvc
        .perform(
            as(mockMvc, get("/api/shifts"), organisation, "SCHEDULER")
                .param("from", DAY_START)
                .param("to", DAY_END))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  /** Defines the shift, expects it created, and returns where to read it back. */
  private String definedShift(String organisation, String start, String end) throws Exception {
    return mockMvc
        .perform(
            as(mockMvc, post("/api/shifts"), organisation, "SCHEDULER")
                .content(shiftBody(start, end)))
        .andExpect(status().isCreated())
        .andReturn()
        .getResponse()
        .getHeader("Location");
  }

  /** Reads the shift back and expects it to start and end exactly at the instants given. */
  private void assertReadBackAs(String organisation, String location, String start, String end)
      throws Exception {
    mockMvc
        .perform(as(mockMvc, get(location), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.start").value(start))
        .andExpect(jsonPath("$.end").value(end));
  }

  /** Every shift of the organisation overlapping a span; a refused one must not be. */
  private void assertNoShiftWithin(String organisation, String from, String to) throws Exception {
    mockMvc
        .perform(
            as(mockMvc, get("/api/shifts"), organisation, "SCHEDULER")
                .param("from", from)
                .param("to", to))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  /**
   * Lists the organisation's shifts over the span, expects it refused as invalid input in a problem
   * detail, and returns that detail.
   */
  private String refusedListing(String organisation, String from, String to) throws Exception {
    String body =
        mockMvc
            .perform(
                as(mockMvc, get("/api/shifts"), organisation, "SCHEDULER")
                    .param("from", from)
                    .param("to", to))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Invalid request"))
            .andExpect(jsonPath("$.detail").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.detail");
  }

  /** A refused generation must leave no roster behind. */
  private void assertNoRoster(String organisation) throws Exception {
    mockMvc
        .perform(as(mockMvc, get("/api/rosters"), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  // --- POST /api/shifts -------------------------------------------------------------------------

  @Test
  void GivenAShiftEndingPastWhatADateTimeHolds_WhenDefined_ThenItIsABadRequestAndNothingIsStored()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    String body = refusedShift(organisation, SHIFT_START, BEYOND_A_DATE_TIME);

    assertThat(body).doesNotContain("1000000000");
    assertNoShiftOnTheDay(organisation);
  }

  @Test
  void GivenAShiftEndingPastWhatTheDatabaseHolds_WhenDefined_ThenItIsABadRequestAndNothingIsStored()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    String body = refusedShift(organisation, SHIFT_START, BEYOND_THE_DATABASE);

    assertThat(body).doesNotContain("300000");
    assertNoShiftOnTheDay(organisation);
  }

  @Test
  void
      GivenAShiftStartingBeforeWhatTheDatabaseHolds_WhenDefined_ThenItIsABadRequestAndNothingIsStored()
          throws Exception {
    String organisation = anOrganisation(mockMvc);

    String body = refusedShift(organisation, BEFORE_THE_DATABASE, SHIFT_END);

    assertThat(body).doesNotContain("-5000");
    assertNoShiftOnTheDay(organisation);
  }

  /**
   * Distinct as sent, so the domain's own check that a span ends after it starts passes; it is the
   * stored span that would have no length.
   */
  @Test
  void GivenAShiftWhoseEndsFallInOneMicrosecond_WhenDefined_ThenItIsABadRequestAndNothingIsStored()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    String body = refusedShift(organisation, WITHIN_A_MICROSECOND_START, WITHIN_A_MICROSECOND_END);

    assertThat(body).doesNotContain("000000100").doesNotContain("000000400");
    assertNoShiftOnTheDay(organisation);
  }

  /** ADR-040: precision beyond the microsecond is stored to the microsecond, not refused. */
  @Test
  void GivenAShiftDeclaredBeyondMicroseconds_WhenDefined_ThenItIsStored() throws Exception {
    String organisation = anOrganisation(mockMvc);

    mockMvc
        .perform(
            as(mockMvc, post("/api/shifts"), organisation, "SCHEDULER")
                .content(
                    shiftBody("2026-12-01T08:00:00.000000100Z", "2026-12-01T16:00:00.000000900Z")))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            as(mockMvc, get("/api/shifts"), organisation, "SCHEDULER")
                .param("from", DAY_START)
                .param("to", DAY_END))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  // --- POST /api/shifts, at each limit (#273, finding 6) ----------------------------------------

  /** The limit is the limit: the earliest instant stored faithfully is still taken. */
  @Test
  void GivenAShiftStartingAtTheEarliestStorableInstant_WhenDefined_ThenItIsReadBackAsSent()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    String location = definedShift(organisation, EARLIEST_STORABLE, EIGHT_HOURS_AFTER_THE_EARLIEST);

    assertReadBackAs(organisation, location, EARLIEST_STORABLE, EIGHT_HOURS_AFTER_THE_EARLIEST);
  }

  /**
   * Inside what PostgreSQL holds but before what pgjdbc writes faithfully: accepted, it would be
   * stored as {@code -infinity} and answered as created.
   */
  @Test
  void
      GivenAShiftStartingAMicrosecondBeforeTheEarliestStorableInstant_WhenDefined_ThenItIsABadRequestAndNothingIsStored()
          throws Exception {
    String organisation = anOrganisation(mockMvc);

    String body =
        refusedShift(organisation, JUST_BEFORE_THE_EARLIEST, EIGHT_HOURS_AFTER_THE_EARLIEST);

    assertThat(body).doesNotContain("4713").doesNotContain("4712");
    assertNoShiftWithin(organisation, EARLIEST_STORABLE, EIGHT_HOURS_AFTER_THE_EARLIEST);
  }

  /** The limit is the limit: the latest instant {@code timestamptz} holds is still taken. */
  @Test
  void GivenAShiftEndingAtTheLatestStorableInstant_WhenDefined_ThenItIsReadBackAsSent()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    String location = definedShift(organisation, EIGHT_HOURS_BEFORE_THE_LATEST, LATEST_STORABLE);

    assertReadBackAs(organisation, location, EIGHT_HOURS_BEFORE_THE_LATEST, LATEST_STORABLE);
  }

  @Test
  void
      GivenAShiftEndingAMicrosecondPastTheLatestStorableInstant_WhenDefined_ThenItIsABadRequestAndNothingIsStored()
          throws Exception {
    String organisation = anOrganisation(mockMvc);

    String body = refusedShift(organisation, EIGHT_HOURS_BEFORE_THE_LATEST, JUST_PAST_THE_LATEST);

    assertThat(body).doesNotContain("294277").doesNotContain("294276");
    assertNoShiftWithin(organisation, EIGHT_HOURS_BEFORE_THE_LATEST, LATEST_STORABLE);
  }

  // --- POST /api/shifts, rounded as the driver rounds (#273, finding 7) -------------------------

  /**
   * Rounded half-up, both ends are stored at the same microsecond, so the span is refused here. A
   * check that truncated would pass it, and the table's CHECK would then fail it as a 500.
   */
  @Test
  void
      GivenAShiftWhoseEndsRoundUpToOneMicrosecond_WhenDefined_ThenItIsABadRequestAndNothingIsStored()
          throws Exception {
    String organisation = anOrganisation(mockMvc);

    String body = refusedShift(organisation, ROUNDING_TOGETHER_START, ROUNDING_TOGETHER_END);

    assertThat(body).doesNotContain("000000600").doesNotContain("000001400");
    assertNoShiftOnTheDay(organisation);
  }

  /**
   * Rounded half-up, the ends are stored a microsecond apart, so the span is taken, and is read
   * back as the driver stored it. A check that truncated would refuse it.
   */
  @Test
  void GivenAShiftWhoseEndsRoundToAdjacentMicroseconds_WhenDefined_ThenItIsStoredRoundedHalfUp()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    String location = definedShift(organisation, ROUNDING_APART_START, ROUNDING_APART_END);

    assertReadBackAs(
        organisation, location, ROUNDING_APART_START_AS_STORED, ROUNDING_APART_END_AS_STORED);
  }

  // --- POST /api/rosters/generations ------------------------------------------------------------

  @Test
  void
      GivenAGenerationSpanEndingPastWhatADateTimeHolds_WhenStarted_ThenItIsABadRequestAndNothingIsStored()
          throws Exception {
    String organisation = anOrganisation(mockMvc);

    String body = refusedGeneration(organisation, DAY_START, BEYOND_A_DATE_TIME);

    assertThat(body).doesNotContain("1000000000");
    assertNoRoster(organisation);
  }

  @Test
  void
      GivenAGenerationSpanEndingPastWhatTheDatabaseHolds_WhenStarted_ThenItIsABadRequestAndNothingIsStored()
          throws Exception {
    String organisation = anOrganisation(mockMvc);

    String body = refusedGeneration(organisation, DAY_START, BEYOND_THE_DATABASE);

    assertThat(body).doesNotContain("300000");
    assertNoRoster(organisation);
  }

  @Test
  void
      GivenAGenerationSpanStartingBeforeWhatTheDatabaseHolds_WhenStarted_ThenItIsABadRequestAndNothingIsStored()
          throws Exception {
    String organisation = anOrganisation(mockMvc);

    String body = refusedGeneration(organisation, BEFORE_THE_DATABASE, DAY_END);

    assertThat(body).doesNotContain("-5000");
    assertNoRoster(organisation);
  }

  @Test
  void
      GivenAGenerationSpanWhoseEndsFallInOneMicrosecond_WhenStarted_ThenItIsABadRequestAndNothingIsStored()
          throws Exception {
    String organisation = anOrganisation(mockMvc);

    String body =
        refusedGeneration(organisation, WITHIN_A_MICROSECOND_START, WITHIN_A_MICROSECOND_END);

    assertThat(body).doesNotContain("000000100").doesNotContain("000000400");
    assertNoRoster(organisation);
  }

  /** ADR-040, as for a shift: the span is generated over, stored to the microsecond. */
  @Test
  void GivenAGenerationSpanDeclaredBeyondMicroseconds_WhenStarted_ThenADraftIsGenerated()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    generateDraft(
        mockMvc, organisation, "2026-12-01T00:00:00.000000100Z", "2026-12-02T00:00:00.000000900Z");

    mockMvc
        .perform(as(mockMvc, get("/api/rosters"), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  // --- GET /api/shifts (#273, finding 2) --------------------------------------------------------

  @Test
  void GivenAListingEndingPastWhatTheDatabaseHolds_WhenListed_ThenItIsABadRequestNamingNoBound()
      throws Exception {
    String detail = refusedListing(anOrganisation(mockMvc), AN_ORDINARY_FROM, BEYOND_THE_DATABASE);

    assertThat(detail).doesNotContain("300000").doesNotContain("2026");
  }

  @Test
  void GivenAListingEndingPastWhatADateTimeHolds_WhenListed_ThenItIsABadRequestNamingNoBound()
      throws Exception {
    String detail = refusedListing(anOrganisation(mockMvc), AN_ORDINARY_FROM, BEYOND_A_DATE_TIME);

    assertThat(detail).doesNotContain("1000000000").doesNotContain("2026");
  }
}
