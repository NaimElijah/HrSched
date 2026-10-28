package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.anOrganisation;
import static io.github.naimelijah.hrsched.scheduling.adapter.in.web.SchedulingHttpRequests.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.naimelijah.hrsched.shared.testsupport.PostgresBackedApplication;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A span the store can hold but generation cannot count is refused when it is sent (#273, finding
 * 1).
 *
 * <p>Generation counts each shift's length and position in nanoseconds, as a {@code long}. A shift
 * longer than that can count, about 292 years, is storable, and then fails every generation that
 * overlaps it; as shifts are never deleted, the organisation would stay stuck. The limit is
 * technical, the same one {@code UnrepresentableConstraintHttpTest} holds working-time values to,
 * and says nothing about how long a shift may sensibly be. Past it, a span is a 400 problem detail
 * repeating no value sent (ADR-024), with nothing stored. At it, at the microsecond precision a
 * span is stored at, it is still taken.
 *
 * <p>Every instant here is invented: the start is an ordinary one, and each end lies only at or
 * just past the limit from it, well inside what the store holds.
 *
 * <p>Annotated identically to the other HTTP tests so they all share one Spring context.
 */
@Import(PostgresBackedApplication.class)
@SpringBootTest
@AutoConfigureMockMvc
class UncountableSpanHttpTest {

  /** An invented ordinary instant, where every span below starts. */
  private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

  /** The day after it, the end of the span looked in for anything stored. */
  private static final Instant DAY_AFTER_START = START.plus(Duration.ofDays(1));

  /**
   * The longest span whose nanoseconds a {@code long} counts, at the microsecond a span is stored
   * at. Technical, not a rule.
   */
  private static final Duration MOST_COUNTABLE =
      Duration.ofNanos(Long.MAX_VALUE).truncatedTo(ChronoUnit.MICROS);

  /** A span ending exactly the most countable length after {@link #START}. */
  private static final Instant END_AT_THE_LIMIT = START.plus(MOST_COUNTABLE);

  /** A microsecond past it: a length no {@code long} counts in nanoseconds. */
  private static final Instant END_PAST_THE_LIMIT = END_AT_THE_LIMIT.plus(1, ChronoUnit.MICROS);

  @Autowired private MockMvc mockMvc;

  private static String shiftBody(Instant start, Instant end) {
    return "{\"start\":\"%s\",\"end\":\"%s\",\"zone\":\"UTC\",\"requiredHeadcount\":1}"
        .formatted(start, end);
  }

  /** What a detail must not repeat of a span: either end, or either end's year. */
  private static void assertRepeatsNeitherEnd(String body, Instant end) {
    assertThat(body)
        .doesNotContain(START.toString())
        .doesNotContain(end.toString())
        .doesNotContain(Integer.toString(START.atZone(ZoneOffset.UTC).getYear()))
        .doesNotContain(Integer.toString(end.atZone(ZoneOffset.UTC).getYear()));
  }

  @Test
  void GivenAShiftLongerThanNanosecondsCount_WhenDefined_ThenItIsABadRequestAndNothingIsStored()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    String body =
        mockMvc
            .perform(
                as(mockMvc, post("/api/shifts"), organisation, "SCHEDULER")
                    .content(shiftBody(START, END_PAST_THE_LIMIT)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertRepeatsNeitherEnd(body, END_PAST_THE_LIMIT);
    mockMvc
        .perform(
            as(mockMvc, get("/api/shifts"), organisation, "SCHEDULER")
                .param("from", START.toString())
                .param("to", DAY_AFTER_START.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  /** The limit is the limit: the longest span nanoseconds count is still a shift. */
  @Test
  void GivenAShiftExactlyAsLongAsNanosecondsCount_WhenDefined_ThenItIsStoredAsSent()
      throws Exception {
    String organisation = anOrganisation(mockMvc);

    String location =
        mockMvc
            .perform(
                as(mockMvc, post("/api/shifts"), organisation, "SCHEDULER")
                    .content(shiftBody(START, END_AT_THE_LIMIT)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader("Location");

    mockMvc
        .perform(as(mockMvc, get(location), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.start").value(START.toString()))
        .andExpect(jsonPath("$.end").value(END_AT_THE_LIMIT.toString()));
  }

  /** A generation's span is counted the same way, so it is held to the same limit. */
  @Test
  void
      GivenAGenerationSpanLongerThanNanosecondsCount_WhenStarted_ThenItIsABadRequestAndNothingIsStored()
          throws Exception {
    String organisation = anOrganisation(mockMvc);

    String body =
        mockMvc
            .perform(
                as(mockMvc, post("/api/rosters/generations"), organisation, "SCHEDULER")
                    .content(
                        "{\"start\":\"%s\",\"end\":\"%s\"}".formatted(START, END_PAST_THE_LIMIT)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertRepeatsNeitherEnd(body, END_PAST_THE_LIMIT);
    mockMvc
        .perform(as(mockMvc, get("/api/rosters"), organisation, "SCHEDULER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }
}
