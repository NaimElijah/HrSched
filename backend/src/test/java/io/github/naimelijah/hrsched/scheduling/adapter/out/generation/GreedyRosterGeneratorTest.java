package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Specification of the baseline the solver is measured against: every promise in {@link
 * RosterGeneratorContract}, plus what only greedy does. A benchmark against a baseline that broke
 * the rules would prove nothing.
 *
 * <p>It is asserted on for what it promises, and deliberately not for roster quality, which it
 * makes no attempt at (ADR-044).
 *
 * <p>Every constraint value below is invented to force a specific outcome and says nothing about
 * any jurisdiction.
 */
class GreedyRosterGeneratorTest extends RosterGeneratorContract {

  private final GreedyRosterGenerator generator = new GreedyRosterGenerator();

  @Override
  protected RosterGenerator generator() {
    return generator;
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
}
