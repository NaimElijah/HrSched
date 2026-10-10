package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Specification of the working-time constraints.
 *
 * <p>EVERY THRESHOLD HERE IS INVENTED. Nine hours of rest, ten hours a day, three consecutive days:
 * none of these come from any statute, and none should be read as a claim about one. They are
 * arbitrary numbers chosen to exercise the arithmetic, which is the whole point of constraints
 * being configuration — the code has no opinion about what the limits are.
 */
class ConstraintSetTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final EmployeeId ADA = EmployeeId.newId();

  /** Work on a September day, its days counted in UTC; a case about another zone builds its own. */
  private static WorkingPeriod on(String day, String from, String to) {
    return new WorkingPeriod(
        new TimeRange(
            Instant.parse("2026-09-%sT%s:00Z".formatted(day, from)),
            Instant.parse("2026-09-%sT%s:00Z".formatted(day, to))),
        ZoneOffset.UTC);
  }

  /** UTC+9 all year, so its day boundary is never UTC's. */
  private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

  private static WorkingPeriod inTokyo(String start, String end) {
    return new WorkingPeriod(new TimeRange(Instant.parse(start), Instant.parse(end)), TOKYO);
  }

  private static ConstraintSet setOf(WorkingTimeConstraint... constraints) {
    return new ConstraintSet(ACME, 1, List.of(constraints));
  }

  @Test
  void GivenAnEmptyConstraintSet_WhenCheckingAnySchedule_ThenNothingIsViolated() {
    EmployeeSchedule punishing =
        new EmployeeSchedule(ADA, List.of(on("07", "00:00", "23:00"), on("08", "00:00", "23:00")));

    assertThat(ConstraintSet.initial(ACME).violationsFor(punishing)).isEmpty();
  }

  @Test
  void GivenAMinimumRest_WhenTwoAssignmentsAreTooClose_ThenItIsViolated() {
    ConstraintSet rules = setOf(new MinimumRestBetweenAssignments(Duration.ofHours(9)));
    EmployeeSchedule tight =
        new EmployeeSchedule(ADA, List.of(on("07", "08:00", "16:00"), on("07", "20:00", "23:00")));

    assertThat(rules.violationsFor(tight))
        .singleElement()
        .satisfies(violation -> assertThat(violation.constraint()).contains("minimum rest"));
  }

  @Test
  void GivenAMinimumRest_WhenTheGapIsLongEnough_ThenItIsSatisfied() {
    ConstraintSet rules = setOf(new MinimumRestBetweenAssignments(Duration.ofHours(9)));
    EmployeeSchedule spaced =
        new EmployeeSchedule(ADA, List.of(on("07", "08:00", "16:00"), on("08", "08:00", "16:00")));

    assertThat(rules.violationsFor(spaced)).isEmpty();
  }

  @Test
  void GivenAMinimumRest_WhenAssignmentsOverlap_ThenItIsViolated() {
    ConstraintSet rules = setOf(new MinimumRestBetweenAssignments(Duration.ofHours(1)));
    EmployeeSchedule overlapping =
        new EmployeeSchedule(ADA, List.of(on("07", "08:00", "16:00"), on("07", "12:00", "20:00")));

    assertThat(rules.violationsFor(overlapping))
        .singleElement()
        .satisfies(violation -> assertThat(violation.detail()).contains("overlap"));
  }

  @Test
  void GivenADailyMaximum_WhenOneDayExceedsIt_ThenItIsViolated() {
    ConstraintSet rules = setOf(new MaximumDailyWorkingTime(Duration.ofHours(10)));
    EmployeeSchedule longDay = new EmployeeSchedule(ADA, List.of(on("07", "06:00", "20:00")));

    assertThat(rules.violationsFor(longDay)).hasSize(1);
  }

  @Test
  void GivenADailyMaximum_WhenTwoShortShiftsShareADayAndExceedIt_ThenItIsViolated() {
    ConstraintSet rules = setOf(new MaximumDailyWorkingTime(Duration.ofHours(10)));
    EmployeeSchedule split =
        new EmployeeSchedule(ADA, List.of(on("07", "06:00", "12:00"), on("07", "13:00", "19:00")));

    assertThat(rules.violationsFor(split))
        .describedAs("a daily cap must count the whole day, not each assignment separately")
        .hasSize(1);
  }

  @Test
  void GivenAWeeklyMaximum_WhenTheTotalExceedsIt_ThenItIsViolated() {
    ConstraintSet rules = setOf(new MaximumWeeklyWorkingTime(Duration.ofHours(20)));
    EmployeeSchedule busy =
        new EmployeeSchedule(
            ADA,
            List.of(
                on("07", "08:00", "18:00"),
                on("08", "08:00", "18:00"),
                on("09", "08:00", "18:00")));

    assertThat(rules.violationsFor(busy)).hasSize(1);
  }

  @Test
  void GivenAConsecutiveDayLimit_WhenTooManyDaysRunTogether_ThenItIsViolated() {
    ConstraintSet rules = setOf(new MaximumConsecutiveWorkingDays(3));
    EmployeeSchedule fourInARow =
        new EmployeeSchedule(
            ADA,
            List.of(
                on("07", "08:00", "12:00"),
                on("08", "08:00", "12:00"),
                on("09", "08:00", "12:00"),
                on("10", "08:00", "12:00")));

    assertThat(rules.violationsFor(fourInARow)).hasSize(1);
  }

  @Test
  void GivenAConsecutiveDayLimit_WhenADayIsSkipped_ThenTheRunResets() {
    ConstraintSet rules = setOf(new MaximumConsecutiveWorkingDays(2));
    EmployeeSchedule withARestDay =
        new EmployeeSchedule(
            ADA,
            List.of(
                on("07", "08:00", "12:00"),
                on("08", "08:00", "12:00"),
                on("10", "08:00", "12:00")));

    assertThat(rules.violationsFor(withARestDay)).isEmpty();
  }

  @Test
  void GivenSeveralRules_WhenAScheduleBreaksMoreThanOne_ThenEveryBreachIsReported() {
    ConstraintSet rules =
        setOf(
            new MaximumDailyWorkingTime(Duration.ofHours(4)),
            new MaximumWeeklyWorkingTime(Duration.ofHours(5)));
    EmployeeSchedule tooMuch = new EmployeeSchedule(ADA, List.of(on("07", "06:00", "18:00")));

    assertThat(rules.violationsFor(tooMuch))
        .describedAs("a scheduler needs to see every problem, not just the first")
        .hasSize(2);
  }

  @Test
  void GivenAConstraintSet_WhenRevised_ThenTheVersionAdvancesAndTheOldOneIsUnchanged() {
    ConstraintSet first = ConstraintSet.initial(ACME);
    ConstraintSet second = first.revisedTo(List.of(new MaximumConsecutiveWorkingDays(5)));

    assertThat(first.version()).isEqualTo(1);
    assertThat(first.constraints()).isEmpty();
    assertThat(second.version()).isEqualTo(2);
    assertThat(second.constraints()).hasSize(1);
  }

  @Test
  void GivenAMeaninglessThreshold_WhenConfiguringAConstraint_ThenItIsRejected() {
    assertThatThrownBy(() -> new MinimumRestBetweenAssignments(Duration.ZERO))
        .isInstanceOf(InvalidConstraintException.class);
    assertThatThrownBy(() -> new MaximumDailyWorkingTime(Duration.ofHours(-1)))
        .isInstanceOf(InvalidConstraintException.class);
    assertThatThrownBy(() -> new MaximumConsecutiveWorkingDays(0))
        .isInstanceOf(InvalidConstraintException.class);
  }

  /**
   * R36. Midnight to 06:00 and 08:00 to 14:00 in Tokyo, both on the 7th there, are twelve hours on
   * one day. Counted in UTC they fall seven hours on the 6th and five on the 7th, and a daily cap
   * of ten would pass a day nobody in Tokyo worked under it.
   */
  @Test
  void GivenADailyMaximum_WhenTwoShiftsShareALocalDayButNotAUtcOne_ThenItIsViolated() {
    ConstraintSet rules = setOf(new MaximumDailyWorkingTime(Duration.ofHours(10)));
    EmployeeSchedule sameLocalDay =
        new EmployeeSchedule(
            ADA,
            List.of(
                inTokyo("2026-09-06T15:00:00Z", "2026-09-06T21:00:00Z"),
                inTokyo("2026-09-06T23:00:00Z", "2026-09-07T05:00:00Z")));

    assertThat(rules.violationsFor(sameLocalDay)).hasSize(1);
  }

  /**
   * R36. Early on the 7th, late on the 8th and early on the 9th in Tokyo is three days running
   * there. In UTC the first falls on the 6th and the other two on the 8th, which no run of more
   * than one day would notice.
   */
  @Test
  void GivenAConsecutiveDayLimit_WhenARunExistsOnlyInLocalDays_ThenItIsViolated() {
    ConstraintSet rules = setOf(new MaximumConsecutiveWorkingDays(2));
    EmployeeSchedule threeLocalDaysRunning =
        new EmployeeSchedule(
            ADA,
            List.of(
                inTokyo("2026-09-06T17:00:00Z", "2026-09-06T19:00:00Z"),
                inTokyo("2026-09-08T11:00:00Z", "2026-09-08T13:00:00Z"),
                inTokyo("2026-09-08T17:00:00Z", "2026-09-08T19:00:00Z")));

    assertThat(rules.violationsFor(threeLocalDaysRunning)).hasSize(1);
  }
}
