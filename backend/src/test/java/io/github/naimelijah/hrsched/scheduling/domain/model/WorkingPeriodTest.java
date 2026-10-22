package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * How one piece of work falls on the calendar days of its own zone (R36). Generation states the
 * daily cap per day from exactly this, so it counts days as publication does.
 */
class WorkingPeriodTest {

  private static WorkingPeriod working(String start, String end, ZoneId zone) {
    return new WorkingPeriod(new TimeRange(Instant.parse(start), Instant.parse(end)), zone);
  }

  @Test
  void GivenAPeriodCrossingMidnight_WhenSplitIntoDays_ThenEachDayHoldsItsShare() {
    WorkingPeriod night = working("2026-10-05T22:00:00Z", "2026-10-06T06:00:00Z", ZoneOffset.UTC);

    assertThat(night.timePerDay())
        .containsExactly(
            entry(LocalDate.parse("2026-10-05"), Duration.ofHours(2)),
            entry(LocalDate.parse("2026-10-06"), Duration.ofHours(6)));
  }

  @Test
  void GivenAPeriodEndingExactlyAtMidnight_WhenSplitIntoDays_ThenTheNextDayHoldsNothing() {
    WorkingPeriod late = working("2026-10-05T16:00:00Z", "2026-10-06T00:00:00Z", ZoneOffset.UTC);

    assertThat(late.timePerDay())
        .containsExactly(entry(LocalDate.parse("2026-10-05"), Duration.ofHours(8)));
  }

  /** The clocks in London go back on 2026-10-25, so midnight to midnight that day is 25 hours. */
  @Test
  void GivenTheClocksGoingBack_WhenSplitIntoDays_ThenThatDayIsTwentyFiveHoursLong() {
    WorkingPeriod wholeDay =
        working("2026-10-24T23:00:00Z", "2026-10-26T00:00:00Z", ZoneId.of("Europe/London"));

    assertThat(wholeDay.timePerDay())
        .containsExactly(entry(LocalDate.parse("2026-10-25"), Duration.ofHours(25)));
  }

  @Test
  void GivenAZoneAheadOfUtc_WhenSplitIntoDays_ThenTheDayIsThatZones() {
    WorkingPeriod tokyoMorning =
        working("2026-09-06T15:00:00Z", "2026-09-06T21:00:00Z", ZoneId.of("Asia/Tokyo"));

    assertThat(tokyoMorning.timePerDay())
        .containsExactly(entry(LocalDate.parse("2026-09-07"), Duration.ofHours(6)));
  }
}
