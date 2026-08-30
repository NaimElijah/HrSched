package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The four working-time rules, driven directly rather than through a {@link ConstraintSet}.
 *
 * <p>EVERY THRESHOLD BELOW IS INVENTED. They are chosen to sit either side of a boundary and say
 * nothing about any jurisdiction. No number in this file comes from a statute, and none may be
 * copied into production as though it did.
 *
 * <p>Each rule's {@code name()} is asserted by hand rather than in a table, because those four
 * strings are a wire contract: they travel in the 422 body and are displayed to a scheduler, so
 * renaming one silently changes what a user reads.
 */
class WorkingTimeConstraintTest {

  private static final EmployeeId ADA = EmployeeId.newId();

  private static TimeRange on(String day, String from, String to) {
    return new TimeRange(
        Instant.parse("2026-09-%sT%s:00:00Z".formatted(day, from)),
        Instant.parse("2026-09-%sT%s:00:00Z".formatted(day, to)));
  }

  private static EmployeeSchedule working(TimeRange... periods) {
    return new EmployeeSchedule(ADA, List.of(periods));
  }

  /** Every way a threshold can be meaningless, one row each. */
  private static Stream<ThrowingCallable> meaninglessThresholds() {
    return Stream.of(
        () -> new MinimumRestBetweenAssignments(Duration.ZERO),
        () -> new MinimumRestBetweenAssignments(Duration.ofHours(-1)),
        () -> new MaximumDailyWorkingTime(Duration.ZERO),
        () -> new MaximumDailyWorkingTime(Duration.ofHours(-1)),
        () -> new MaximumWeeklyWorkingTime(Duration.ZERO),
        () -> new MaximumWeeklyWorkingTime(Duration.ofHours(-1)),
        () -> new MaximumConsecutiveWorkingDays(0),
        () -> new MaximumConsecutiveWorkingDays(-1));
  }

  /** Every rule that takes a duration, so a null one can be rejected in one sentence. */
  private static Stream<ThrowingCallable> nullThresholds() {
    return Stream.of(
        () -> new MinimumRestBetweenAssignments(null),
        () -> new MaximumDailyWorkingTime(null),
        () -> new MaximumWeeklyWorkingTime(null));
  }

  @ParameterizedTest
  @MethodSource("meaninglessThresholds")
  void GivenAThresholdThatMeansNothing_WhenConfiguringARule_ThenItIsRejected(
      ThrowingCallable configuring) {
    assertThatThrownBy(configuring).isInstanceOf(InvalidConstraintException.class);
  }

  @ParameterizedTest
  @MethodSource("nullThresholds")
  void GivenANullThreshold_WhenConfiguringARule_ThenItIsRejected(ThrowingCallable configuring) {
    assertThatThrownBy(configuring).isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenRestExactlyAtTheMinimum_WhenCheckingASchedule_ThenNothingIsViolated() {
    MinimumRestBetweenAssignments rule =
        new MinimumRestBetweenAssignments(Duration.ofHours(8)); // invented

    assertThat(rule.violatedBy(working(on("07", "08", "16"), on("08", "00", "06")))).isEmpty();
  }

  @Test
  void GivenRestShorterThanTheMinimum_WhenCheckingASchedule_ThenItIsViolated() {
    MinimumRestBetweenAssignments rule =
        new MinimumRestBetweenAssignments(Duration.ofHours(8)); // invented

    assertThat(rule.violatedBy(working(on("07", "08", "16"), on("07", "20", "23")))).isPresent();
  }

  @Test
  void GivenOverlappingPeriods_WhenCheckingRest_ThenItIsViolated() {
    MinimumRestBetweenAssignments rule =
        new MinimumRestBetweenAssignments(Duration.ofHours(8)); // invented

    assertThat(rule.violatedBy(working(on("07", "08", "16"), on("07", "12", "20")))).isPresent();
  }

  @Test
  void GivenADayExactlyAtTheDailyCap_WhenCheckingASchedule_ThenNothingIsViolated() {
    MaximumDailyWorkingTime rule = new MaximumDailyWorkingTime(Duration.ofHours(8)); // invented

    assertThat(rule.violatedBy(working(on("07", "08", "16")))).isEmpty();
  }

  @Test
  void GivenADayOverTheDailyCap_WhenCheckingASchedule_ThenItIsViolated() {
    MaximumDailyWorkingTime rule = new MaximumDailyWorkingTime(Duration.ofHours(8)); // invented

    assertThat(rule.violatedBy(working(on("07", "08", "17")))).isPresent();
  }

  @Test
  void GivenTotalTimeExactlyAtTheWeeklyCap_WhenCheckingASchedule_ThenNothingIsViolated() {
    MaximumWeeklyWorkingTime rule = new MaximumWeeklyWorkingTime(Duration.ofHours(16)); // invented

    assertThat(rule.violatedBy(working(on("07", "08", "16"), on("08", "08", "16")))).isEmpty();
  }

  @Test
  void GivenTotalTimeOverTheWeeklyCap_WhenCheckingASchedule_ThenItIsViolated() {
    MaximumWeeklyWorkingTime rule = new MaximumWeeklyWorkingTime(Duration.ofHours(15)); // invented

    assertThat(rule.violatedBy(working(on("07", "08", "16"), on("08", "08", "16")))).isPresent();
  }

  @Test
  void GivenARunExactlyAtTheConsecutiveCap_WhenCheckingASchedule_ThenNothingIsViolated() {
    MaximumConsecutiveWorkingDays rule = new MaximumConsecutiveWorkingDays(2); // invented

    assertThat(rule.violatedBy(working(on("07", "08", "16"), on("08", "08", "16")))).isEmpty();
  }

  @Test
  void GivenARunOverTheConsecutiveCap_WhenCheckingASchedule_ThenItIsViolated() {
    MaximumConsecutiveWorkingDays rule = new MaximumConsecutiveWorkingDays(2); // invented

    assertThat(
            rule.violatedBy(
                working(on("07", "08", "16"), on("08", "08", "16"), on("09", "08", "16"))))
        .isPresent();
  }

  @Test
  void GivenARestDayBreakingTheRun_WhenCheckingConsecutiveDays_ThenNothingIsViolated() {
    MaximumConsecutiveWorkingDays rule = new MaximumConsecutiveWorkingDays(2); // invented

    assertThat(
            rule.violatedBy(
                working(on("07", "08", "16"), on("08", "08", "16"), on("10", "08", "16"))))
        .isEmpty();
  }

  /** Every rule, so one failure cannot mask the other three. All figures invented. */
  private static Stream<WorkingTimeConstraint> everyRule() {
    return Stream.of(
        new MinimumRestBetweenAssignments(Duration.ofHours(8)),
        new MaximumDailyWorkingTime(Duration.ofHours(8)),
        new MaximumWeeklyWorkingTime(Duration.ofHours(17)),
        new MaximumConsecutiveWorkingDays(3));
  }

  @ParameterizedTest
  @MethodSource("everyRule")
  void GivenAnEmptySchedule_WhenCheckingAnyRule_ThenNothingIsViolated(WorkingTimeConstraint rule) {
    assertThat(rule.violatedBy(working())).isEmpty();
  }

  @Test
  void GivenEachRule_WhenAskedItsName_ThenItNamesItselfAsTheWireExpects() {
    assertThat(new MinimumRestBetweenAssignments(Duration.ofHours(8)).name())
        .isEqualTo("minimum rest between assignments");
    assertThat(new MaximumDailyWorkingTime(Duration.ofHours(8)).name())
        .isEqualTo("maximum daily working time");
    assertThat(new MaximumWeeklyWorkingTime(Duration.ofHours(17)).name())
        .isEqualTo("maximum weekly working time");
    assertThat(new MaximumConsecutiveWorkingDays(3).name())
        .isEqualTo("maximum consecutive working days");
  }

  @Test
  void GivenTwoRulesSharingAThreshold_WhenComparingThem_ThenTheyAreEqual() {
    assertThat(new MaximumDailyWorkingTime(Duration.ofHours(8)))
        .isEqualTo(new MaximumDailyWorkingTime(Duration.ofHours(8)));
  }

  /**
   * A fifth rule would have to be handled by the response mapper's exhaustive switch, which the
   * compiler enforces. This says the same thing about the wire: four rules, and adding one is a
   * deliberate act rather than an accident.
   */
  @Test
  void GivenTheSealedHierarchy_WhenListingIt_ThenExactlyFourRulesExist() {
    assertThat(WorkingTimeConstraint.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(
            MinimumRestBetweenAssignments.class,
            MaximumDailyWorkingTime.class,
            MaximumWeeklyWorkingTime.class,
            MaximumConsecutiveWorkingDays.class);
  }
}
