package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Specification of the placeholder generator.
 *
 * <p>It is asserted on for what it promises — never breaking a rule, and leaving a shift short
 * rather than filling it illegally — and deliberately not for roster quality, which it makes no
 * attempt at. See ADR-005.
 *
 * <p>Every constraint value below is invented to force a specific outcome and says nothing about
 * any jurisdiction.
 */
class GreedyRosterGeneratorTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private final GreedyRosterGenerator generator = new GreedyRosterGenerator();

  private static Shift shift(String start, String end, int headcount, Set<QualificationId> needed) {
    return new Shift(
        ShiftId.newId(),
        ACME,
        new TimeRange(Instant.parse(start), Instant.parse(end)),
        headcount,
        needed);
  }

  private static RosterCandidate anyone(String name) {
    return new RosterCandidate(EmployeeId.newId(), name, Set.of(), List.of());
  }

  @Test
  void GivenNobodyQualified_WhenGenerating_ThenTheShiftIsLeftShortRatherThanFilled() {
    Shift needsATicket =
        shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of(QualificationId.newId()));

    List<Assignment> assignments =
        generator.generate(
            List.of(needsATicket), List.of(anyone("Ada")), ConstraintSet.initial(ACME));

    assertThat(assignments)
        .describedAs("a roster honest about a gap beats one that hides it")
        .isEmpty();
  }

  @Test
  void GivenOnePersonAndTwoOverlappingShifts_WhenGenerating_ThenTheyCoverOnlyOne() {
    Shift morning = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of());
    Shift clashing = shift("2026-10-05T12:00:00Z", "2026-10-05T20:00:00Z", 1, Set.of());

    List<Assignment> assignments =
        generator.generate(
            List.of(morning, clashing), List.of(anyone("Ada")), ConstraintSet.initial(ACME));

    assertThat(assignments).hasSize(1);
  }

  @Test
  void GivenAConstraintTheAssignmentWouldBreak_WhenGenerating_ThenNobodyIsAssigned() {
    Shift eightHours = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of());
    // Invented: one hour a day, which no eight-hour shift can satisfy.
    ConstraintSet oneHourDays =
        ConstraintSet.initial(ACME)
            .revisedTo(List.of(new MaximumDailyWorkingTime(Duration.ofHours(1))));

    List<Assignment> assignments =
        generator.generate(List.of(eightHours), List.of(anyone("Ada")), oneHourDays);

    assertThat(assignments).isEmpty();
  }

  @Test
  void GivenEnoughPeople_WhenGenerating_ThenHeadcountIsMetWithoutRepeatingAnyone() {
    Shift needsTwo = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 2, Set.of());

    List<Assignment> assignments =
        generator.generate(
            List.of(needsTwo),
            List.of(anyone("Ada"), anyone("Grace"), anyone("Alan")),
            ConstraintSet.initial(ACME));

    assertThat(assignments).hasSize(2);
    assertThat(assignments.stream().map(Assignment::employee).distinct()).hasSize(2);
  }
}
