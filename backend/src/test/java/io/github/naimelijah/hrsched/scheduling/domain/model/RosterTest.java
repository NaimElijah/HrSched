package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Specification of the roster lifecycle. */
class RosterTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final EmployeeId ADA = EmployeeId.newId();
  private static final TimeRange WEEK =
      new TimeRange(Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-14T00:00:00Z"));
  private static final TimeRange MONDAY =
      new TimeRange(Instant.parse("2026-09-07T08:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"));
  private static final TimeRange MONDAY_AFTERNOON =
      new TimeRange(Instant.parse("2026-09-07T12:00:00Z"), Instant.parse("2026-09-07T20:00:00Z"));
  private static final TimeRange TUESDAY =
      new TimeRange(Instant.parse("2026-09-08T08:00:00Z"), Instant.parse("2026-09-08T16:00:00Z"));

  private static Roster draft() {
    return new Roster(RosterId.newId(), ACME, WEEK);
  }

  @Test
  void GivenANewRoster_WhenCreated_ThenItIsAnEmptyDraft() {
    Roster roster = draft();

    assertThat(roster.status()).isEqualTo(RosterStatus.DRAFT);
    assertThat(roster.assignments()).isEmpty();
    assertThat(roster.validatedAgainstConstraintVersion()).isNull();
  }

  @Test
  void GivenAnAssignedDraft_WhenTheAssignmentIsRemoved_ThenItIsGone() {
    ShiftId shift = ShiftId.newId();

    Roster roster = draft().with(new Assignment(shift, ADA)).without(new Assignment(shift, ADA));

    assertThat(roster.assignments()).isEmpty();
  }

  @Test
  void GivenTwoAssignments_WhenOneIsRemoved_ThenOnlyTheOtherRemains() {
    ShiftId monday = ShiftId.newId();
    ShiftId tuesday = ShiftId.newId();

    Roster roster =
        draft()
            .with(new Assignment(monday, ADA))
            .with(new Assignment(tuesday, ADA))
            .without(new Assignment(monday, ADA));

    assertThat(roster.assignments()).containsExactly(new Assignment(tuesday, ADA));
  }

  /** A no-op would tell a Scheduler a change happened which did not. */
  @Test
  void GivenAnAssignmentThatWasNeverMade_WhenRemovingIt_ThenItIsRefused() {
    ShiftId shift = ShiftId.newId();

    assertThatThrownBy(() -> draft().without(new Assignment(shift, ADA)))
        .isInstanceOf(UnknownAssignmentException.class);
  }

  @Test
  void GivenAPublishedRoster_WhenRemovingAnAssignment_ThenItIsRefused() {
    ShiftId shift = ShiftId.newId();
    Roster published = draft().with(new Assignment(shift, ADA)).publishedUnder(1);

    assertThatThrownBy(() -> published.without(new Assignment(shift, ADA)))
        .isInstanceOf(RosterNotEditableException.class);
  }

  @Test
  void GivenADraft_WhenAssigningSomeone_ThenTheAssignmentIsHeld() {
    ShiftId shift = ShiftId.newId();

    Roster roster = draft().with(new Assignment(shift, ADA));

    assertThat(roster.assignments()).containsExactly(new Assignment(shift, ADA));
  }

  @Test
  void GivenAnAssignment_WhenMakingItTwice_ThenItIsRejected() {
    ShiftId shift = ShiftId.newId();
    Roster roster = draft().with(new Assignment(shift, ADA));

    assertThatThrownBy(() -> roster.with(new Assignment(shift, ADA)))
        .isInstanceOf(DuplicateAssignmentException.class);
  }

  @Test
  void GivenADuplicateInAGeneratedList_WhenReplacingAssignments_ThenItIsRejected() {
    ShiftId shift = ShiftId.newId();

    assertThatThrownBy(
            () ->
                draft()
                    .withAssignments(
                        List.of(new Assignment(shift, ADA), new Assignment(shift, ADA))))
        .describedAs("generation replaces the list wholesale and must honour the same invariant")
        .isInstanceOf(DuplicateAssignmentException.class);
  }

  @Test
  void GivenADraft_WhenPublished_ThenItRecordsTheConstraintVersionThatApprovedIt() {
    Roster published = draft().publishedUnder(7);

    assertThat(published.status()).isEqualTo(RosterStatus.PUBLISHED);
    assertThat(published.validatedAgainstConstraintVersion())
        .describedAs("a rule change later must never make this roster retroactively illegal")
        .isEqualTo(7);
  }

  @Test
  void GivenAPublishedRoster_WhenChangingIt_ThenItIsRefused() {
    Roster published = draft().publishedUnder(1);

    assertThatThrownBy(() -> published.with(new Assignment(ShiftId.newId(), ADA)))
        .describedAs("people plan their lives around a published roster")
        .isInstanceOf(RosterNotEditableException.class);
    assertThatThrownBy(() -> published.withAssignments(List.of()))
        .isInstanceOf(RosterNotEditableException.class);
  }

  @Test
  void GivenAPublishedRoster_WhenPublishedAgain_ThenItIsRefused() {
    Roster published = draft().publishedUnder(1);

    assertThatThrownBy(() -> published.publishedUnder(2))
        .isInstanceOf(RosterNotEditableException.class);
  }

  @Test
  void GivenAPublishedRoster_WhenSuperseded_ThenItKeepsItsHistory() {
    Roster superseded =
        draft().with(new Assignment(ShiftId.newId(), ADA)).publishedUnder(3).superseded();

    assertThat(superseded.status()).isEqualTo(RosterStatus.SUPERSEDED);
    assertThat(superseded.assignments()).hasSize(1);
    assertThat(superseded.validatedAgainstConstraintVersion()).isEqualTo(3);
  }

  @Test
  void GivenADraft_WhenSuperseding_ThenItIsRefused() {
    assertThatThrownBy(() -> draft().superseded()).isInstanceOf(RosterNotEditableException.class);
  }

  @Test
  void GivenARosterWithAssignments_WhenBuildingSchedules_ThenEachEmployeeGetsTheirOwn() {
    ShiftId monday = ShiftId.newId();
    ShiftId tuesday = ShiftId.newId();
    EmployeeId grace = EmployeeId.newId();
    Roster roster =
        draft()
            .with(new Assignment(monday, ADA))
            .with(new Assignment(tuesday, ADA))
            .with(new Assignment(monday, grace));

    List<EmployeeSchedule> schedules =
        roster.schedulesFor(Map.of(monday, MONDAY, tuesday, TUESDAY));

    assertThat(schedules).hasSize(2);
    assertThat(schedules)
        .filteredOn(schedule -> schedule.employee().equals(ADA))
        .singleElement()
        .satisfies(schedule -> assertThat(schedule.workingPeriods()).hasSize(2));
  }

  /**
   * R3, whatever the constraint set says. Building schedules is the step publication cannot skip,
   * so a roster carrying somebody in two places is refused there however the draft was made.
   */
  @Test
  void GivenOnePersonOnTwoOverlappingShifts_WhenBuildingSchedules_ThenItIsRefused() {
    ShiftId day = ShiftId.newId();
    ShiftId afternoon = ShiftId.newId();
    Roster roster = draft().with(new Assignment(day, ADA)).with(new Assignment(afternoon, ADA));

    assertThatThrownBy(() -> roster.schedulesFor(Map.of(day, MONDAY, afternoon, MONDAY_AFTERNOON)))
        .isInstanceOf(OverlappingAssignmentException.class);
  }

  @Test
  void GivenOnePersonOnShiftsThatOnlyTouch_WhenBuildingSchedules_ThenNothingIsRefused() {
    ShiftId day = ShiftId.newId();
    ShiftId evening = ShiftId.newId();
    TimeRange mondayEvening =
        new TimeRange(Instant.parse("2026-09-07T16:00:00Z"), Instant.parse("2026-09-07T20:00:00Z"));
    Roster roster = draft().with(new Assignment(day, ADA)).with(new Assignment(evening, ADA));

    assertThatCode(() -> roster.schedulesFor(Map.of(day, MONDAY, evening, mondayEvening)))
        .describedAs("a period's end is exclusive, so ending as the next begins is no overlap")
        .doesNotThrowAnyException();
  }

  /**
   * Checking one person needs only their own shifts. Assigning somebody by hand asks about them
   * alone, so a colleague's shift that cannot be resolved must neither hide the overlap nor refuse
   * on its own account.
   */
  @Test
  void
      GivenSomebodyElsesShiftCannotBeResolved_WhenCheckingOnePerson_ThenTheirOverlapIsStillFound() {
    ShiftId day = ShiftId.newId();
    ShiftId afternoon = ShiftId.newId();
    Roster roster =
        draft()
            .with(new Assignment(day, ADA))
            .with(new Assignment(afternoon, ADA))
            .with(new Assignment(ShiftId.newId(), EmployeeId.newId()));

    assertThatThrownBy(
            () ->
                roster.requireNoOverlappingWorkFor(
                    ADA, Map.of(day, MONDAY, afternoon, MONDAY_AFTERNOON)))
        .isInstanceOf(OverlappingAssignmentException.class);
  }

  @Test
  void GivenOneOfTheirShiftsCannotBeResolved_WhenCheckingThem_ThenItIsNotVerifiable() {
    ShiftId day = ShiftId.newId();
    Roster roster =
        draft().with(new Assignment(day, ADA)).with(new Assignment(ShiftId.newId(), ADA));

    assertThatThrownBy(() -> roster.requireNoOverlappingWorkFor(ADA, Map.of(day, MONDAY)))
        .describedAs("work that cannot be placed in time cannot be shown not to overlap")
        .isInstanceOf(RosterNotVerifiableException.class);
  }

  /**
   * The overlap only ordering by start time finds. Assigned in this order, Monday's day shift and
   * Monday's afternoon are never next to each other, so comparing assignments in the order they
   * were made, rather than by when they start, would miss it.
   */
  @Test
  void GivenOverlappingShiftsAssignedApart_WhenBuildingSchedules_ThenItIsStillRefused() {
    ShiftId day = ShiftId.newId();
    ShiftId tuesday = ShiftId.newId();
    ShiftId afternoon = ShiftId.newId();
    Roster roster =
        draft()
            .with(new Assignment(day, ADA))
            .with(new Assignment(tuesday, ADA))
            .with(new Assignment(afternoon, ADA));

    assertThatThrownBy(
            () ->
                roster.schedulesFor(
                    Map.of(day, MONDAY, tuesday, TUESDAY, afternoon, MONDAY_AFTERNOON)))
        .isInstanceOf(OverlappingAssignmentException.class);
  }
}
