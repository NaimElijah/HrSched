package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Specification of the placeholder generator: every promise in {@link RosterGeneratorContract},
 * plus what only greedy does.
 *
 * <p>It is asserted on for what it promises, and deliberately not for roster quality, which it
 * makes no attempt at. See ADR-005.
 *
 * <p>Every constraint value below is invented to force a specific outcome and says nothing about
 * any jurisdiction.
 */
class GreedyRosterGeneratorTest extends RosterGeneratorContract {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final GreedyRosterGenerator generator = new GreedyRosterGenerator(meters);

  @Override
  protected RosterGenerator generator() {
    return generator;
  }

  private DistributionSummary summary(String name) {
    return meters.get(name).summary();
  }

  /**
   * Greedy takes shifts in the order they start, so of two it cannot both give one person, that
   * person gets the earlier. The contract asks only that they get one of them.
   */
  @Test
  void GivenTwoShiftsOnOneLocalDay_WhenGeneratingUnderADailyCap_ThenTheEarlierIsTheOneAssigned() {
    ZoneId tokyo = ZoneId.of("Asia/Tokyo");
    Shift lateNight =
        new Shift(
            ShiftId.newId(),
            ACME,
            new TimeRange(
                Instant.parse("2026-09-06T15:00:00Z"), Instant.parse("2026-09-06T21:00:00Z")),
            tokyo,
            1,
            Set.of(),
            false);
    Shift morning =
        new Shift(
            ShiftId.newId(),
            ACME,
            new TimeRange(
                Instant.parse("2026-09-06T23:00:00Z"), Instant.parse("2026-09-07T05:00:00Z")),
            tokyo,
            1,
            Set.of(),
            false);
    // Invented: ten hours a day, two hours short of both shifts together.
    ConstraintSet tenHourDays = rules(new MaximumDailyWorkingTime(Duration.ofHours(10)));

    List<Assignment> added =
        generator.generate(
            request(List.of(morning, lateNight), List.of(anyone("Ada")), tenHourDays));

    assertThat(added).extracting(Assignment::shift).containsExactly(lateNight.id());
  }

  @Test
  void GivenAShiftNobodyCanCover_WhenGenerating_ThenTheGapIsMeasuredRatherThanOnlyLeft() {
    generator.generate(
        request(
            List.of(shift("2026-10-05T09:00:00Z", "2026-10-05T17:00:00Z", 3, Set.of())),
            List.of(),
            ConstraintSet.initial(ACME)));

    assertThat(summary("hrsched.shifts.required").totalAmount()).isEqualTo(3.0);
    assertThat(summary("hrsched.shifts.uncovered").totalAmount()).isEqualTo(3.0);
  }

  @Test
  void GivenAFullyCoveredShift_WhenGenerating_ThenNoGapIsRecorded() {
    generator.generate(
        request(
            List.of(shift("2026-10-05T09:00:00Z", "2026-10-05T17:00:00Z", 1, Set.of())),
            List.of(anyone("Ada")),
            ConstraintSet.initial(ACME)));

    assertThat(summary("hrsched.shifts.required").totalAmount()).isEqualTo(1.0);
    assertThat(summary("hrsched.shifts.uncovered").totalAmount()).isZero();
  }

  @Test
  void GivenAPartlyCoveredShift_WhenGenerating_ThenOnlyTheUnfilledPlacesCount() {
    generator.generate(
        request(
            List.of(shift("2026-10-05T09:00:00Z", "2026-10-05T17:00:00Z", 3, Set.of())),
            List.of(anyone("Ada")),
            ConstraintSet.initial(ACME)));

    assertThat(summary("hrsched.shifts.required").totalAmount()).isEqualTo(3.0);
    assertThat(summary("hrsched.shifts.uncovered").totalAmount()).isEqualTo(2.0);
  }

  /**
   * ADR-045: generation must hold no connection, so asking for it inside a transaction is a mistake
   * in the caller, refused before anything else.
   */
  @Test
  void GivenATransactionAlreadyOpen_WhenGenerating_ThenItIsRefused() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(
              () ->
                  generator.generate(
                      request(List.of(), List.of(anyone("Ada")), ConstraintSet.initial(ACME))))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  /**
   * A generation asked for nothing is a different event from no generation at all, and a metric
   * that only appears on failure cannot show a recovery.
   */
  @Test
  void GivenNoShiftsAtAll_WhenGenerating_ThenTheGenerationIsStillCounted() {
    generator.generate(request(List.of(), List.of(anyone("Ada")), ConstraintSet.initial(ACME)));

    assertThat(summary("hrsched.shifts.required").count()).isEqualTo(1L);
    assertThat(summary("hrsched.shifts.uncovered").count()).isEqualTo(1L);
  }
}
