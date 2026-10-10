package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;
import java.time.ZoneOffset;
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
  private static final TimeRange NEXT_WEEK =
      new TimeRange(Instant.parse("2026-09-14T00:00:00Z"), Instant.parse("2026-09-21T00:00:00Z"));
  private static final TimeRange OVERLAPPING_WEEK =
      new TimeRange(Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-17T00:00:00Z"));
  private static final List<HeldWork> NOTHING_ELSEWHERE = List.of();

  /**
   * When a shift runs, its days counted in UTC; R3 compares instants, so the zone is immaterial.
   */
  private static WorkingPeriod utc(TimeRange period) {
    return new WorkingPeriod(period, ZoneOffset.UTC);
  }

  private static Roster draft() {
    return new Roster(RosterId.newId(), ACME, WEEK);
  }

  private static Roster published(TimeRange period) {
    return new Roster(RosterId.newId(), ACME, period).publishedUnder(1);
  }

  private static HeldWork heldBy(EmployeeId employee, TimeRange when) {
    return new HeldWork(RosterId.newId(), ShiftId.newId(), employee, when);
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
        roster.schedulesFor(Map.of(monday, utc(MONDAY), tuesday, utc(TUESDAY)), NOTHING_ELSEWHERE);

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

    assertThatThrownBy(
            () ->
                roster.schedulesFor(
                    Map.of(day, utc(MONDAY), afternoon, utc(MONDAY_AFTERNOON)), NOTHING_ELSEWHERE))
        .isInstanceOf(OverlappingAssignmentException.class);
  }

  @Test
  void GivenOnePersonOnShiftsThatOnlyTouch_WhenBuildingSchedules_ThenNothingIsRefused() {
    ShiftId day = ShiftId.newId();
    ShiftId evening = ShiftId.newId();
    TimeRange mondayEvening =
        new TimeRange(Instant.parse("2026-09-07T16:00:00Z"), Instant.parse("2026-09-07T20:00:00Z"));
    Roster roster = draft().with(new Assignment(day, ADA)).with(new Assignment(evening, ADA));

    assertThatCode(
            () ->
                roster.schedulesFor(
                    Map.of(day, utc(MONDAY), evening, utc(mondayEvening)), NOTHING_ELSEWHERE))
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
                    ADA,
                    Map.of(day, utc(MONDAY), afternoon, utc(MONDAY_AFTERNOON)),
                    NOTHING_ELSEWHERE))
        .isInstanceOf(OverlappingAssignmentException.class);
  }

  @Test
  void GivenOneOfTheirShiftsCannotBeResolved_WhenCheckingThem_ThenItIsNotVerifiable() {
    ShiftId day = ShiftId.newId();
    Roster roster =
        draft().with(new Assignment(day, ADA)).with(new Assignment(ShiftId.newId(), ADA));

    assertThatThrownBy(
            () ->
                roster.requireNoOverlappingWorkFor(
                    ADA, Map.of(day, utc(MONDAY)), NOTHING_ELSEWHERE))
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
                    Map.of(
                        day, utc(MONDAY), tuesday, utc(TUESDAY), afternoon, utc(MONDAY_AFTERNOON)),
                    NOTHING_ELSEWHERE))
        .isInstanceOf(OverlappingAssignmentException.class);
  }

  /**
   * R35: R3 is judged across every authoritative roster, not within one. A shift running past the
   * end of last week's roster can overlap work on this one, and the person cannot be in both.
   */
  @Test
  void GivenOverlappingWorkHeldOnAnotherRoster_WhenCheckingThem_ThenItIsRefusedNamingThatRoster() {
    ShiftId day = ShiftId.newId();
    HeldWork elsewhere = heldBy(ADA, MONDAY_AFTERNOON);
    Roster roster = draft().with(new Assignment(day, ADA));

    assertThatThrownBy(
            () ->
                roster.requireNoOverlappingWorkFor(
                    ADA, Map.of(day, utc(MONDAY)), List.of(elsewhere)))
        .isInstanceOf(OverlappingAssignmentException.class)
        .hasMessageContaining(elsewhere.roster().value().toString());
  }

  @Test
  void GivenWorkHeldElsewhereThatOnlyTouches_WhenCheckingThem_ThenNothingIsRefused() {
    ShiftId day = ShiftId.newId();
    TimeRange mondayEvening =
        new TimeRange(Instant.parse("2026-09-07T16:00:00Z"), Instant.parse("2026-09-07T20:00:00Z"));
    Roster roster = draft().with(new Assignment(day, ADA));

    assertThatCode(
            () ->
                roster.requireNoOverlappingWorkFor(
                    ADA, Map.of(day, utc(MONDAY)), List.of(heldBy(ADA, mondayEvening))))
        .doesNotThrowAnyException();
  }

  /**
   * R35 lets either roster carry a shift straddling both periods, and generation picks the same
   * person for it again. The same assignment on two rosters is one place to be, not two, so it is
   * no overlap. ADR-033.
   */
  @Test
  void GivenTheSameShiftHeldByThemOnAnotherRoster_WhenCheckingThem_ThenNothingIsRefused() {
    ShiftId overnight = ShiftId.newId();
    HeldWork sameShiftElsewhere = new HeldWork(RosterId.newId(), overnight, ADA, MONDAY);
    Roster roster = draft().with(new Assignment(overnight, ADA));

    assertThatCode(
            () ->
                roster.requireNoOverlappingWorkFor(
                    ADA, Map.of(overnight, utc(MONDAY)), List.of(sameShiftElsewhere)))
        .doesNotThrowAnyException();
  }

  @Test
  void GivenSomebodyElsesOverlappingWorkHeldElsewhere_WhenCheckingThem_ThenNothingIsRefused() {
    ShiftId day = ShiftId.newId();
    Roster roster = draft().with(new Assignment(day, ADA));

    assertThatCode(
            () ->
                roster.requireNoOverlappingWorkFor(
                    ADA, Map.of(day, utc(MONDAY)), List.of(heldBy(EmployeeId.newId(), MONDAY))))
        .doesNotThrowAnyException();
  }

  /**
   * Two rosters published before R35 was enforced may already overlap each other. That is their
   * breach, not this roster's, and refusing here would block every later publication with no remedy
   * the Scheduler could reach from this draft.
   */
  @Test
  void GivenTwoPiecesOfWorkElsewhereOverlappingEachOther_WhenCheckingThem_ThenNothingIsRefused() {
    ShiftId tuesday = ShiftId.newId();
    Roster roster = draft().with(new Assignment(tuesday, ADA));

    assertThatCode(
            () ->
                roster.requireNoOverlappingWorkFor(
                    ADA,
                    Map.of(tuesday, utc(TUESDAY)),
                    List.of(heldBy(ADA, MONDAY), heldBy(ADA, MONDAY_AFTERNOON))))
        .doesNotThrowAnyException();
  }

  /**
   * The overlap that comparing neighbours by start time would miss. Sorted together, the short
   * night shift held elsewhere falls between the long one and this roster's day shift, so only a
   * comparison of every held piece against every own shift finds it.
   */
  @Test
  void GivenLongWorkHeldElsewhereWithShorterWorkStartingInside_WhenCheckingThem_ThenItIsRefused() {
    ShiftId day = ShiftId.newId();
    TimeRange allMonday =
        new TimeRange(Instant.parse("2026-09-07T00:00:00Z"), Instant.parse("2026-09-07T23:00:00Z"));
    TimeRange earlyMonday =
        new TimeRange(Instant.parse("2026-09-07T01:00:00Z"), Instant.parse("2026-09-07T02:00:00Z"));
    Roster roster = draft().with(new Assignment(day, ADA));

    assertThatThrownBy(
            () ->
                roster.requireNoOverlappingWorkFor(
                    ADA,
                    Map.of(day, utc(MONDAY)),
                    List.of(heldBy(ADA, allMonday), heldBy(ADA, earlyMonday))))
        .isInstanceOf(OverlappingAssignmentException.class);
  }

  @Test
  void GivenOverlappingWorkHeldElsewhere_WhenBuildingSchedules_ThenItIsRefused() {
    ShiftId day = ShiftId.newId();
    Roster roster = draft().with(new Assignment(day, ADA));

    assertThatThrownBy(
            () ->
                roster.schedulesFor(
                    Map.of(day, utc(MONDAY)), List.of(heldBy(ADA, MONDAY_AFTERNOON))))
        .isInstanceOf(OverlappingAssignmentException.class);
  }

  /**
   * Work held elsewhere is checked for R3 and nothing more. The working-time rules still judge one
   * roster at a time, so it must not leak into the schedules they are evaluated against.
   */
  @Test
  void GivenWorkHeldElsewhere_WhenBuildingSchedules_ThenOnlyThisRostersWorkIsScheduled() {
    ShiftId day = ShiftId.newId();
    Roster roster = draft().with(new Assignment(day, ADA));

    List<EmployeeSchedule> schedules =
        roster.schedulesFor(Map.of(day, utc(MONDAY)), List.of(heldBy(ADA, TUESDAY)));

    assertThat(schedules)
        .singleElement()
        .satisfies(schedule -> assertThat(schedule.workingPeriods()).containsExactly(utc(MONDAY)));
  }

  @Test
  void GivenAPublishedRosterOverlappingTheReplacement_WhenPublishing_ThenItIsSuperseded() {
    Roster replacement = new Roster(RosterId.newId(), ACME, OVERLAPPING_WEEK);

    assertThat(published(WEEK).isSupersededBy(replacement)).isTrue();
    assertThat(published(WEEK).staysAuthoritativeBeside(replacement)).isFalse();
  }

  @Test
  void
      GivenAPublishedRosterThatOnlyTouchesTheReplacement_WhenPublishing_ThenItStaysAuthoritative() {
    Roster replacement = new Roster(RosterId.newId(), ACME, NEXT_WEEK);

    assertThat(published(WEEK).isSupersededBy(replacement)).isFalse();
    assertThat(published(WEEK).staysAuthoritativeBeside(replacement)).isTrue();
  }

  /**
   * A draft binds nobody. Each predicate is asked about the replacement that would otherwise make
   * it true — one overlapping, one only touching — so the status is what makes both false.
   */
  @Test
  void GivenADraft_WhenAnotherIsPublished_ThenItIsNeitherSupersededNorAuthoritative() {
    assertThat(draft().isSupersededBy(new Roster(RosterId.newId(), ACME, OVERLAPPING_WEEK)))
        .isFalse();
    assertThat(draft().staysAuthoritativeBeside(new Roster(RosterId.newId(), ACME, NEXT_WEEK)))
        .isFalse();
  }

  @Test
  void GivenASupersededRoster_WhenAnotherIsPublished_ThenItIsNotAuthoritative() {
    Roster replacement = new Roster(RosterId.newId(), ACME, NEXT_WEEK);

    assertThat(published(WEEK).superseded().staysAuthoritativeBeside(replacement)).isFalse();
  }

  /**
   * Only supersession needs the identity check. A roster always overlaps itself, so it could never
   * stay authoritative beside itself whatever that check said.
   */
  @Test
  void GivenThePublishedRosterItself_WhenPublishingIt_ThenItDoesNotSupersedeItself() {
    Roster roster = published(WEEK);

    assertThat(roster.isSupersededBy(roster)).isFalse();
  }

  /**
   * Another organisation's roster is never superseded by this one, and its work is never this
   * organisation's evidence. The read is already scoped, so this holds the line if it ever is not.
   */
  @Test
  void GivenAnotherOrganisationsPublishedRoster_WhenPublishing_ThenItIsIgnored() {
    Roster theirs = new Roster(RosterId.newId(), OrganisationId.newId(), WEEK).publishedUnder(1);

    assertThat(theirs.isSupersededBy(new Roster(RosterId.newId(), ACME, OVERLAPPING_WEEK)))
        .isFalse();
    assertThat(theirs.staysAuthoritativeBeside(new Roster(RosterId.newId(), ACME, NEXT_WEEK)))
        .isFalse();
  }
}
