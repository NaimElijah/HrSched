package io.github.naimelijah.hrsched.scheduling.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.scheduling.domain.model.InvalidHeadcountException;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.WorkingTimeConstraint;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What the scheduling driving ports accept, and where they stop short.
 *
 * <p>A command validates that it is complete, not that it is sensible. Which invariant lives where
 * is worth pinning: a headcount below one is refused by {@code Shift} and deliberately not here, so
 * a future contributor "helpfully" duplicating the check has a test telling them it already has a
 * home.
 */
class SchedulingCommandsTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final TimeRange MONDAY_DAY =
      new TimeRange(Instant.parse("2026-09-07T08:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"));

  private static Stream<ThrowingCallable> nullFields() {
    return Stream.of(
        () -> new DefineShiftCommand(null, MONDAY_DAY, ZoneOffset.UTC, 1, Set.of(), false),
        () -> new DefineShiftCommand(ACME, null, ZoneOffset.UTC, 1, Set.of(), false),
        () -> new DefineShiftCommand(ACME, MONDAY_DAY, null, 1, Set.of(), false),
        () -> new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, null, false),
        () -> new GenerateRosterCommand(null, MONDAY_DAY),
        () -> new GenerateRosterCommand(ACME, null),
        () -> new ReviseConstraintsCommand(null, List.of()),
        () -> new ReviseConstraintsCommand(ACME, null));
  }

  @ParameterizedTest
  @MethodSource("nullFields")
  void GivenANullField_WhenConstructingACommand_ThenItIsRejected(ThrowingCallable constructing) {
    assertThatThrownBy(constructing).isInstanceOf(NullPointerException.class);
  }

  @Test
  void GivenAMutableSetOfQualifications_WhenDefiningAShift_ThenLaterChangesAreNotSeen() {
    Set<QualificationId> required = new HashSet<>(Set.of(QualificationId.newId()));
    DefineShiftCommand command =
        new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 1, required, false);

    required.add(QualificationId.newId());

    assertThat(command.requiredQualifications()).hasSize(1);
  }

  @Test
  void GivenAMutableListOfRules_WhenRevisingConstraints_ThenLaterChangesAreNotSeen() {
    List<WorkingTimeConstraint> rules = new ArrayList<>();
    rules.add(new MaximumDailyWorkingTime(Duration.ofHours(8))); // invented
    ReviseConstraintsCommand command = new ReviseConstraintsCommand(ACME, rules);

    rules.clear();

    assertThat(command.constraints()).hasSize(1);
  }

  /**
   * Switching every rule off is a legitimate configuration -- an organisation with no statutory
   * constraints, or one still being set up. The code that would break this is code that invents a
   * default, which is the one thing this domain must never do.
   */
  @Test
  void GivenNoRulesAtAll_WhenRevisingConstraints_ThenItIsAccepted() {
    assertThat(new ReviseConstraintsCommand(ACME, List.of()).constraints()).isEmpty();
  }

  @Test
  void GivenAHeadcountBelowOne_WhenDefiningAShift_ThenTheCommandAcceptsItAndTheDomainRefusesIt() {
    DefineShiftCommand command =
        new DefineShiftCommand(ACME, MONDAY_DAY, ZoneOffset.UTC, 0, Set.of(), false);

    assertThatThrownBy(
            () ->
                new Shift(
                    ShiftId.newId(),
                    command.organisation(),
                    command.period(),
                    ZoneOffset.UTC,
                    command.requiredHeadcount(),
                    command.requiredQualifications(),
                    false))
        .isInstanceOf(InvalidHeadcountException.class);
  }
}
