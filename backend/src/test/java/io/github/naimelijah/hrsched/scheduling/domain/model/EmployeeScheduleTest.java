package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Specification of how a schedule is divided into calendar days.
 *
 * <p>This arithmetic decides what a daily cap and a consecutive-day cap actually mean, so getting
 * it wrong publishes illegal rosters in one direction and refuses legal ones in the other. Days are
 * counted in UTC, which is an acknowledged limitation rather than a decision — see {@link
 * EmployeeSchedule#workingTimePerDay()}.
 */
class EmployeeScheduleTest {

  private static final EmployeeId ADA = EmployeeId.newId();

  private static EmployeeSchedule working(String... instants) {
    List<TimeRange> periods =
        java.util.stream.IntStream.range(0, instants.length / 2)
            .mapToObj(
                index ->
                    new TimeRange(
                        Instant.parse(instants[index * 2]), Instant.parse(instants[index * 2 + 1])))
            .toList();
    return new EmployeeSchedule(ADA, periods);
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
}
