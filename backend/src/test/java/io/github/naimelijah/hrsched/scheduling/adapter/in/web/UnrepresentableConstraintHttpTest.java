package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.as;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.generateDraft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A working-time value too large for the system to compute with is refused when it is configured,
 * and one it can compute with is generated with.
 *
 * <p>The limit is technical, not a working-time ceiling: generation computes in nanoseconds, so a
 * duration must be expressible as a {@code long} count of them. Nothing here encodes a statute or a
 * business number. A value past that limit was either a 500 at once, when even converting the
 * minutes overflowed, or worse, stored, after which every generation the organisation started
 * failed. Either way it must be a 400 problem detail, naming the field and never the value sent
 * (ADR-024), leaving the rules as they were. The value at the limit is the reason the limit is
 * where it is, so a generation under it must still produce a draft.
 *
 * <p>No refusal of a working-time value repeats it either, whatever refused it.
 *
 * <p>Every value here is invented: the rules configured first are chosen only to be recognisable on
 * read-back, the shift and the refused minimum rests only to be ordinary, and the far values only
 * to lie at or past what a {@code long} can count.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class UnrepresentableConstraintHttpTest {

  /**
   * The most whole minutes whose nanoseconds still fit in a {@code long}. Technical, not a rule.
   */
  private static final long MOST_MINUTES_IN_NANOSECONDS =
      Duration.ofNanos(Long.MAX_VALUE).toMinutes();

  /** Invented, recognisable rules stored before each refused revision. */
  private static final String INVENTED_RULES =
      "{\"minimumRestMinutes\":661,\"maximumDailyMinutes\":599,\"maximumWeeklyMinutes\":2399,"
          + "\"maximumConsecutiveDays\":5}";

  /** An invented ordinary day, and eight invented hours on it for the one shift generated. */
  private static final String DAY_START = "2026-12-07T00:00:00Z";

  private static final String DAY_END = "2026-12-08T00:00:00Z";

  private static final String SHIFT_START = "2026-12-07T08:00:00Z";

  private static final String SHIFT_END = "2026-12-07T16:00:00Z";

  /** An invented minimum rest below zero, which the domain refuses. */
  private static final long NEGATIVE_REST_MINUTES = -5;

  @Autowired private MockMvc mockMvc;

  /**
   * The values, one per minute field, each named for why it is refused: the largest a JSON number
   * bound to a {@code long} can be, which overflows converting minutes to seconds; the smallest,
   * which overflows the same way; and one minute past what nanoseconds can count, which converts,
   * and used to be stored.
   */
  static Stream<Arguments> unrepresentableMinutes() {
    return Stream.of("minimumRestMinutes", "maximumDailyMinutes", "maximumWeeklyMinutes")
        .flatMap(
            field ->
                Stream.of(
                        Named.named(
                            "the largest long, overflowing minutes to seconds", Long.MAX_VALUE),
                        Named.named(
                            "the smallest long, overflowing minutes to seconds", Long.MIN_VALUE),
                        Named.named(
                            "one minute past what nanoseconds count",
                            MOST_MINUTES_IN_NANOSECONDS + 1))
                    .map(minutes -> Arguments.of(field, minutes)));
  }

  private String organisationWithInventedRules() throws Exception {
    String organisation = anOrganisation(mockMvc);
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), organisation, "ADMIN").content(INVENTED_RULES))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
    return organisation;
  }

  private void assertTheInventedRulesStand(String organisation) throws Exception {
    mockMvc
        .perform(as(mockMvc, get("/api/constraints"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.minimumRestMinutes").value(661))
        .andExpect(jsonPath("$.maximumDailyMinutes").value(599))
        .andExpect(jsonPath("$.maximumWeeklyMinutes").value(2399))
        .andExpect(jsonPath("$.maximumConsecutiveDays").value(5));
  }

  /**
   * Configures the one field, expects it refused as invalid input in a problem detail, and returns
   * that detail.
   */
  private String refusedRevision(String organisation, String field, long minutes) throws Exception {
    String body =
        mockMvc
            .perform(
                as(mockMvc, put("/api/constraints"), organisation, "ADMIN")
                    .content("{\"%s\":%d}".formatted(field, minutes)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Invalid request"))
            .andExpect(jsonPath("$.detail").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.detail");
  }

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("unrepresentableMinutes")
  void GivenMinutesTooManyToCountInNanoseconds_WhenConfigured_ThenItIsABadRequestAndTheRulesStand(
      String field, long minutes) throws Exception {
    String organisation = organisationWithInventedRules();

    refusedRevision(organisation, field, minutes);

    assertTheInventedRulesStand(organisation);
  }

  /** The detail says which value is wrong without repeating it (ADR-024). */
  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("unrepresentableMinutes")
  void GivenMinutesTooManyToCountInNanoseconds_WhenConfigured_ThenTheDetailNamesTheFieldNotTheValue(
      String field, long minutes) throws Exception {
    String detail = refusedRevision(anOrganisation(mockMvc), field, minutes);

    assertThat(detail).contains(field).doesNotContain(Long.toString(minutes));
  }

  /** The limit is the limit: the last whole minute nanoseconds can count is still configured. */
  @ParameterizedTest
  @ValueSource(strings = {"minimumRestMinutes", "maximumDailyMinutes", "maximumWeeklyMinutes"})
  void GivenTheMostMinutesNanosecondsCanCount_WhenConfigured_ThenItIsStoredAsSent(String field)
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), organisation, "ADMIN")
                .content("{\"%s\":%d}".formatted(field, MOST_MINUTES_IN_NANOSECONDS)))
        .andExpect(status().isOk());

    mockMvc
        .perform(as(mockMvc, get("/api/constraints"), organisation, "ADMIN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$." + field).value(MOST_MINUTES_IN_NANOSECONDS));
  }

  // --- #273, finding 8: what is stored at the limit is what generation can compute with -------

  /**
   * The limit exists so that generation can count the value in nanoseconds. Configured, it must
   * still be generated with: one shift, somebody who can work it, and a draft covering it, which
   * the value is far too large to forbid.
   */
  @ParameterizedTest
  @ValueSource(strings = {"minimumRestMinutes", "maximumDailyMinutes", "maximumWeeklyMinutes"})
  void GivenTheMostMinutesNanosecondsCanCount_WhenGenerating_ThenADraftCoversTheShift(String field)
      throws Exception {
    String organisation = anOrganisation(mockMvc);
    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), organisation, "ADMIN")
                .content("{\"%s\":%d}".formatted(field, MOST_MINUTES_IN_NANOSECONDS)))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            as(mockMvc, post("/api/employees"), organisation, "ADMIN")
                .content("{\"name\":\"Ada\"}"))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            as(mockMvc, post("/api/shifts"), organisation, "SCHEDULER")
                .content(
                    "{\"start\":\"%s\",\"end\":\"%s\",\"zone\":\"UTC\",\"requiredHeadcount\":1}"
                        .formatted(SHIFT_START, SHIFT_END)))
        .andExpect(status().isCreated());

    String roster = generateDraft(mockMvc, organisation, DAY_START, DAY_END);

    mockMvc
        .perform(as(mockMvc, get("/api/rosters/" + roster), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.assignments.length()").value(1));
  }

  // --- #273, finding 12: a refused minimum rest is not repeated ---------------------------------
}
