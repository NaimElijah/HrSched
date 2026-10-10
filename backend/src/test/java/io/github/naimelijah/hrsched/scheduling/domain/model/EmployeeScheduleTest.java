package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Specification of how a schedule is divided into calendar days.
 *
 * <p>This arithmetic decides what a daily cap and a consecutive-day cap actually mean, so getting
 * it wrong publishes illegal rosters in one direction and refuses legal ones in the other. Each
 * period's days are counted in its own shift's zone (R36). The cases written in UTC keep their
 * arithmetic simple; the zone cases below are the ones where UTC would give the wrong answer.
 */
class EmployeeScheduleTest {

  private static final EmployeeId ADA = EmployeeId.newId();

  /** UTC+9 all year, so a day boundary there is never UTC's. */
  private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

  /** Observes daylight saving, so some of its local days are 23 or 25 hours long. */
  private static final ZoneId LONDON = ZoneId.of("Europe/London");

  private static WorkingPeriod in(ZoneId zone, String start, String end) {
    return new WorkingPeriod(new TimeRange(Instant.parse(start), Instant.parse(end)), zone);
  }

  private static EmployeeSchedule working(String... instants) {
    List<TimeRange> periods =
        java.util.stream.IntStream.range(0, instants.length / 2)
            .mapToObj(
                index ->
                    new TimeRange(
                        Instant.parse(instants[index * 2]), Instant.parse(instants[index * 2 + 1])))
            .toList();
    return new EmployeeSchedule(
        ADA, periods.stream().map(period -> new WorkingPeriod(period, ZoneOffset.UTC)).toList());
  }

  @Test
  void GivenAShiftCrossingMidnight_WhenTotallingPerDay_ThenEachDayGetsOnlyItsOwnHours() {
    EmployeeSchedule schedule = working("2026-10-05T22:00:00Z", "2026-10-06T06:00:00Z");

    assertThat(schedule.workingTimePerDay())
        .containsExactly(
            org.assertj.core.data.MapEntry.entry(LocalDate.of(2026, 10, 5), Duration.ofHours(2)),
            org.assertj.core.data.MapEntry.entry(LocalDate.of(2026, 10, 6), Duration.ofHours(6)));
  }

  @Test
  void GivenAnOvernightAndANextDayShift_WhenTotallingPerDay_ThenTheSecondDayIncludesBoth() {
    EmployeeSchedule schedule =
        working(
            "2026-10-05T22:00:00Z", "2026-10-06T06:00:00Z",
            "2026-10-06T08:00:00Z", "2026-10-06T20:00:00Z");

    assertThat(schedule.workingTimePerDay())
        .describedAs(
            "six hours spilling over plus twelve worked on the day is eighteen, not twelve")
        .containsEntry(LocalDate.of(2026, 10, 6), Duration.ofHours(18));
  }

  @Test
  void GivenAShiftEndingExactlyAtMidnight_WhenListingWorkingDays_ThenTheNextDayIsNotOne() {
    EmployeeSchedule schedule = working("2026-10-05T16:00:00Z", "2026-10-06T00:00:00Z");

    assertThat(schedule.workingDays())
        .describedAs("a period's end is exclusive, so no time at all falls on the 6th")
        .containsExactly(LocalDate.of(2026, 10, 5));
  }

  @Test
  void GivenAShiftSpanningWholeDays_WhenListingWorkingDays_ThenEveryDayTouchedIsCounted() {
    EmployeeSchedule schedule = working("2026-10-05T23:00:00Z", "2026-10-08T01:00:00Z");

    assertThat(schedule.workingDays())
        .containsExactly(
            LocalDate.of(2026, 10, 5),
            LocalDate.of(2026, 10, 6),
            LocalDate.of(2026, 10, 7),
            LocalDate.of(2026, 10, 8));
  }

  @Test
  void GivenNothingToWork_WhenTotallingPerDay_ThenThereAreNoDays() {
    assertThat(working().workingTimePerDay()).isEmpty();
    assertThat(working().workingDays()).isEmpty();
  }

  /**
   * R36, the case the zone exists for. Tokyo's 22:00 to 02:00 is 13:00 to 17:00 in UTC, all on one
   * UTC day, but it straddles midnight where the shift is worked.
   */
  @Test
  void GivenAShiftCrossingLocalMidnight_WhenTotallingPerDay_ThenEachLocalDayGetsItsOwnHours() {
    EmployeeSchedule schedule =
        new EmployeeSchedule(
            ADA, List.of(in(TOKYO, "2026-10-05T13:00:00Z", "2026-10-05T17:00:00Z")));

    assertThat(schedule.workingTimePerDay())
        .describedAs("counted in UTC this would be four hours, all on the 5th")
        .containsExactly(
            org.assertj.core.data.MapEntry.entry(LocalDate.of(2026, 10, 5), Duration.ofHours(2)),
            org.assertj.core.data.MapEntry.entry(LocalDate.of(2026, 10, 6), Duration.ofHours(2)));
  }

  /**
   * The clocks go forward in London at 01:00 UTC on 29 March 2026, so that local day is 23 hours
   * long. Twenty-four hours of work from its start reach an hour into the next day.
   */
  @Test
  void GivenADayTheClocksGoForward_WhenTotallingPerDay_ThenItHoldsTwentyThreeHours() {
    EmployeeSchedule schedule =
        new EmployeeSchedule(
            ADA, List.of(in(LONDON, "2026-03-29T00:00:00Z", "2026-03-30T00:00:00Z")));

    assertThat(schedule.workingTimePerDay())
        .containsExactly(
            org.assertj.core.data.MapEntry.entry(LocalDate.of(2026, 3, 29), Duration.ofHours(23)),
            org.assertj.core.data.MapEntry.entry(LocalDate.of(2026, 3, 30), Duration.ofHours(1)));
  }

  /**
   * The clocks go back in London at 01:00 UTC on 25 October 2026, so that local day is 25 hours
   * long, from 23:00 UTC on the 24th to midnight UTC on the 26th.
   */
  @Test
  void GivenADayTheClocksGoBack_WhenTotallingPerDay_ThenItHoldsTwentyFiveHours() {
    EmployeeSchedule schedule =
        new EmployeeSchedule(
            ADA, List.of(in(LONDON, "2026-10-24T23:00:00Z", "2026-10-26T00:00:00Z")));

    assertThat(schedule.workingTimePerDay())
        .containsExactly(
            org.assertj.core.data.MapEntry.entry(LocalDate.of(2026, 10, 25), Duration.ofHours(25)));
  }

  /**
   * One person working shifts in two zones: each period lands on the calendar date of its own zone,
   * and work on the same date adds up whichever zone it was in (ADR-034). The Tokyo early shift is
   * still the 4th in UTC, so only counting it in Tokyo puts it on the 5th beside the other.
   */
  @Test
  void GivenWorkInTwoZones_WhenTotallingPerDay_ThenEachCountsOnItsOwnCalendarDate() {
    EmployeeSchedule schedule =
        new EmployeeSchedule(
            ADA,
            List.of(
                in(TOKYO, "2026-10-04T16:00:00Z", "2026-10-04T20:00:00Z"),
                in(ZoneOffset.UTC, "2026-10-05T10:00:00Z", "2026-10-05T14:00:00Z")));

    assertThat(schedule.workingTimePerDay())
        .containsExactly(
            org.assertj.core.data.MapEntry.entry(LocalDate.of(2026, 10, 5), Duration.ofHours(8)));
  }
}
