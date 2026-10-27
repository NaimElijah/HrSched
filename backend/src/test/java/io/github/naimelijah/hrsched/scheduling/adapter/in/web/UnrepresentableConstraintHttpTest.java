package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.as;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import java.time.Duration;
import java.util.stream.Stream;
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
 * A working-time value too large for the system to compute with is refused when it is configured.
 *
 * <p>The limit is technical, not a working-time ceiling: generation computes in nanoseconds, so a
 * duration must be expressible as a {@code long} count of them. Nothing here encodes a statute or a
 * business number. A value past that limit was either a 500 at once, when even converting the
 * minutes overflowed, or worse, stored, after which every generation the organisation started
 * failed. Either way it must be a 400 problem detail, leaving the rules as they were.
 *
 * <p>The rules configured first are invented, chosen only to be recognisable on read-back.
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

  @Autowired private MockMvc mockMvc;

  /**
   * The values, one per minute field: the largest a JSON number bound to a {@code long} can be,
   * which overflows converting minutes to seconds; the smallest, which overflows the same way; and
   * one minute past what nanoseconds can count, which converts, and used to be stored.
   */
  static Stream<Arguments> unrepresentableMinutes() {
    return Stream.of("minimumRestMinutes", "maximumDailyMinutes", "maximumWeeklyMinutes")
        .flatMap(
            field ->
                Stream.of(Long.MAX_VALUE, Long.MIN_VALUE, MOST_MINUTES_IN_NANOSECONDS + 1)
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

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("unrepresentableMinutes")
  void GivenMinutesTooManyToCountInNanoseconds_WhenConfigured_ThenItIsABadRequestAndTheRulesStand(
      String field, long minutes) throws Exception {
    String organisation = organisationWithInventedRules();

    mockMvc
        .perform(
            as(mockMvc, put("/api/constraints"), organisation, "ADMIN")
                .content("{\"%s\":%d}".formatted(field, minutes)))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").isNotEmpty());

    assertTheInventedRulesStand(organisation);
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
}
