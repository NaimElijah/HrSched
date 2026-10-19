package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  private static final ConstraintSet NO_RULES = ConstraintSet.initial(ACME);

  /** When a roster in these tests was published. Any instant would do. */
  private static final Instant PUBLISHED_AT = Instant.parse("2026-09-01T09:00:00Z");

  /** When it was superseded: later, as a supersession always is. */
  private static final Instant SUPERSEDED_AT = Instant.parse("2026-09-03T15:30:00Z");

  private static final Duration A_MICROSECOND = Duration.of(1, ChronoUnit.MICROS);

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
    return new Roster(RosterId.newId(), ACME, period).publishedUnder(1, PUBLISHED_AT);
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
    Roster published = draft().with(new Assignment(shift, ADA)).publishedUnder(1, PUBLISHED_AT);

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
    Roster published = draft().publishedUnder(7, PUBLISHED_AT);

    assertThat(published.status()).isEqualTo(RosterStatus.PUBLISHED);
    assertThat(published.validatedAgainstConstraintVersion())
        .describedAs("a rule change later must never make this roster retroactively illegal")
        .isEqualTo(7);
  }

  @Test
  void GivenAPublishedRoster_WhenChangingIt_ThenItIsRefused() {
    Roster published = draft().publishedUnder(1, PUBLISHED_AT);

    assertThatThrownBy(() -> published.with(new Assignment(ShiftId.newId(), ADA)))
        .describedAs("people plan their lives around a published roster")
        .isInstanceOf(RosterNotEditableException.class);
    assertThatThrownBy(() -> published.withAssignments(List.of()))
        .isInstanceOf(RosterNotEditableException.class);
  }

  @Test
  void GivenAPublishedRoster_WhenPublishedAgain_ThenItIsRefused() {
    Roster published = draft().publishedUnder(1, PUBLISHED_AT);

    assertThatThrownBy(() -> published.publishedUnder(2, PUBLISHED_AT))
        .isInstanceOf(RosterNotEditableException.class);
  }

  @Test
  void GivenAPublishedRoster_WhenSuperseded_ThenItKeepsItsHistory() {
    Roster superseded =
        draft()
            .with(new Assignment(ShiftId.newId(), ADA))
            .publishedUnder(3, PUBLISHED_AT)
            .superseded(SUPERSEDED_AT);

    assertThat(superseded.status()).isEqualTo(RosterStatus.SUPERSEDED);
    assertThat(superseded.assignments()).hasSize(1);
    assertThat(superseded.validatedAgainstConstraintVersion()).isEqualTo(3);
  }

  @Test
  void GivenANewRoster_WhenCreated_ThenNoMomentOfRecordIsSet() {
    Roster roster = draft();

    assertThat(roster.publishedAt()).isNull();
    assertThat(roster.supersededAt()).isNull();
  }

  @Test
  void GivenADraft_WhenPublished_ThenItRecordsWhenItWasPublished() {
    Roster published = draft().publishedUnder(1, PUBLISHED_AT);

    assertThat(published.publishedAt()).isEqualTo(PUBLISHED_AT);
    assertThat(published.supersededAt()).isNull();
  }

  /**
   * Superseding adds a fact and changes none: the roster still says when it was published, so what
   * was authoritative before the supersession can be reconstructed (R37).
   */
  @Test
  void GivenAPublishedRoster_WhenSuperseded_ThenItRecordsWhenAndKeepsWhenItWasPublished() {
    Roster superseded = draft().publishedUnder(1, PUBLISHED_AT).superseded(SUPERSEDED_AT);

    assertThat(superseded.publishedAt()).isEqualTo(PUBLISHED_AT);
    assertThat(superseded.supersededAt()).isEqualTo(SUPERSEDED_AT);
  }

  @Test
  void GivenAPublishedRoster_WhenSupersededBeforeItWasPublished_ThenItIsRefused() {
    Roster published = draft().publishedUnder(1, PUBLISHED_AT);

    assertThatThrownBy(() -> published.superseded(PUBLISHED_AT.minus(A_MICROSECOND)))
        .describedAs("a roster cannot stop governing before it began to")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void GivenAMomentFinerThanAMicrosecond_WhenPublishing_ThenItIsRefused() {
    assertThatThrownBy(() -> draft().publishedUnder(1, PUBLISHED_AT.plusNanos(1)))
        .describedAs("history is recorded to the microsecond, and a finer moment cannot be kept")
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void GivenAMomentFinerThanAMicrosecond_WhenSuperseding_ThenItIsRefused() {
    Roster published = draft().publishedUnder(1, PUBLISHED_AT);

    assertThatThrownBy(() -> published.superseded(SUPERSEDED_AT.plusNanos(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void GivenNoHistory_WhenChoosingTheMomentOfRecord_ThenItIsTheClockToTheMicrosecond() {
    Instant clock = Instant.parse("2026-09-05T10:00:00.123456789Z");

    assertThat(Roster.nextMomentOfRecord(clock, List.of(draft())))
        .isEqualTo(Instant.parse("2026-09-05T10:00:00.123456Z"));
  }

  @Test
  void GivenHistoryBehindTheClock_WhenChoosingTheMomentOfRecord_ThenItIsTheClock() {
    Instant clock = SUPERSEDED_AT.plus(Duration.ofDays(1));

    assertThat(Roster.nextMomentOfRecord(clock, List.of(published(WEEK).superseded(SUPERSEDED_AT))))
        .isEqualTo(clock);
  }

  /**
   * A clock that reads no later than the history already recorded - a fixed clock, a coarse one, or
   * one stepped back - would otherwise end one roster's authority before it began, or begin and end
   * another's at one instant. Moving one microsecond past the latest moment recorded keeps every
   * authority interval non-empty and in order.
   */
  @Test
  void GivenHistoryReachingTheClock_WhenChoosingTheMomentOfRecord_ThenItIsAMicrosecondLater() {
    Roster supersededLast = published(WEEK).superseded(SUPERSEDED_AT);
    Roster publishedFirst = published(NEXT_WEEK);

    assertThat(
            Roster.nextMomentOfRecord(
                PUBLISHED_AT, List.of(publishedFirst, supersededLast, draft())))
        .isEqualTo(SUPERSEDED_AT.plus(A_MICROSECOND));
  }

  @Test
  void GivenADraft_WhenSuperseding_ThenItIsRefused() {
    assertThatThrownBy(() -> draft().superseded(SUPERSEDED_AT))
        .isInstanceOf(RosterNotEditableException.class);
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
        roster.schedulesFor(Map.of(monday, utc(MONDAY), tuesday, utc(TUESDAY)));

    assertThat(schedules).hasSize(2);
    assertThat(schedules)
        .filteredOn(schedule -> schedule.employee().equals(ADA))
        .singleElement()
        .satisfies(schedule -> assertThat(schedule.workingPeriods()).hasSize(2));
  }

  /**
   * R3, whatever the constraint set says. Judging a roster is the step publication cannot skip, so
   * a roster carrying somebody in two places is refused there however the draft was made.
   */
  @Test
  void GivenOnePersonOnTwoOverlappingShifts_WhenJudged_ThenTheOverlapIsListed() {
    ShiftId day = ShiftId.newId();
    ShiftId afternoon = ShiftId.newId();
    Roster roster = draft().with(new Assignment(day, ADA)).with(new Assignment(afternoon, ADA));

    assertThat(
            roster.violationsUnder(
                NO_RULES,
                Map.of(day, utc(MONDAY), afternoon, utc(MONDAY_AFTERNOON)),
                NOTHING_ELSEWHERE))
        .containsExactly(
            ConstraintViolation.overlappingWork(ADA, day, afternoon, Optional.empty()));
  }

  @Test
  void GivenOnePersonOnShiftsThatOnlyTouch_WhenJudged_ThenNothingIsListed() {
    ShiftId day = ShiftId.newId();
    ShiftId evening = ShiftId.newId();
    TimeRange mondayEvening =
        new TimeRange(Instant.parse("2026-09-07T16:00:00Z"), Instant.parse("2026-09-07T20:00:00Z"));
    Roster roster = draft().with(new Assignment(day, ADA)).with(new Assignment(evening, ADA));

    assertThat(
            roster.violationsUnder(
                NO_RULES, Map.of(day, utc(MONDAY), evening, utc(mondayEvening)), NOTHING_ELSEWHERE))
        .describedAs("a period's end is exclusive, so ending as the next begins is no overlap")
        .isEmpty();
  }

  /** A listing names every clash, not the first: three shifts at once are three pairs. */
  @Test
  void GivenThreeMutuallyOverlappingShifts_WhenJudged_ThenEveryPairIsListed() {
    ShiftId day = ShiftId.newId();
    ShiftId afternoon = ShiftId.newId();
    ShiftId midday = ShiftId.newId();
    TimeRange mondayMidday =
        new TimeRange(Instant.parse("2026-09-07T11:00:00Z"), Instant.parse("2026-09-07T14:00:00Z"));
    Roster roster =
        draft()
            .with(new Assignment(day, ADA))
            .with(new Assignment(afternoon, ADA))
            .with(new Assignment(midday, ADA));

    assertThat(
            roster.violationsUnder(
                NO_RULES,
                Map.of(
                    day, utc(MONDAY), afternoon, utc(MONDAY_AFTERNOON), midday, utc(mondayMidday)),
                NOTHING_ELSEWHERE))
        .containsExactly(
            ConstraintViolation.overlappingWork(ADA, day, midday, Optional.empty()),
            ConstraintViolation.overlappingWork(ADA, day, afternoon, Optional.empty()),
            ConstraintViolation.overlappingWork(ADA, midday, afternoon, Optional.empty()));
  }

  /**
   * Somebody in two places at once has a schedule nobody can work, so its rest and its daily total
   * mean nothing until the overlap is resolved. Their overlap is listed and their working time is
   * not; a colleague's working time is judged as usual. The values are invented.
   */
  @Test
  void GivenAnOverlapForOneAndADailyBreachForAnother_WhenJudged_ThenEachIsListedOnce() {
    ShiftId day = ShiftId.newId();
    ShiftId afternoon = ShiftId.newId();
    EmployeeId grace = EmployeeId.newId();
    Roster roster =
        draft()
            .with(new Assignment(day, ADA))
            .with(new Assignment(afternoon, ADA))
            .with(new Assignment(day, grace));
    // Invented: a cap below one eight-hour shift, so Grace's day breaks it on its own.
    ConstraintSet sixHoursADay =
        NO_RULES.revisedTo(List.of(new MaximumDailyWorkingTime(Duration.ofHours(6))));

    List<ConstraintViolation> violations =
        roster.violationsUnder(
            sixHoursADay,
            Map.of(day, utc(MONDAY), afternoon, utc(MONDAY_AFTERNOON)),
            NOTHING_ELSEWHERE);

    assertThat(violations)
        .extracting(ConstraintViolation::employee, ConstraintViolation::constraint)
        .containsExactly(
            tuple(ADA, ConstraintViolation.OVERLAPPING_WORK),
            tuple(grace, "maximum daily working time"));
  }

  @Test
  void GivenAnAssignedShiftThatCannotBePlaced_WhenJudged_ThenItIsNotVerifiable() {
    Roster roster = draft().with(new Assignment(ShiftId.newId(), ADA));

    assertThatThrownBy(() -> roster.violationsUnder(NO_RULES, Map.of(), NOTHING_ELSEWHERE))
        .isInstanceOf(RosterNotVerifiableException.class);
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
  void GivenOverlappingShiftsAssignedApart_WhenJudged_ThenTheOverlapIsStillListed() {
    ShiftId day = ShiftId.newId();
    ShiftId tuesday = ShiftId.newId();
    ShiftId afternoon = ShiftId.newId();
    Roster roster =
        draft()
            .with(new Assignment(day, ADA))
            .with(new Assignment(tuesday, ADA))
            .with(new Assignment(afternoon, ADA));

    assertThat(
            roster.violationsUnder(
                NO_RULES,
                Map.of(day, utc(MONDAY), tuesday, utc(TUESDAY), afternoon, utc(MONDAY_AFTERNOON)),
                NOTHING_ELSEWHERE))
        .containsExactly(
            ConstraintViolation.overlappingWork(ADA, day, afternoon, Optional.empty()));
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
  void GivenOverlappingWorkHeldElsewhere_WhenJudged_ThenItIsListedNamingThatRoster() {
    ShiftId day = ShiftId.newId();
    HeldWork elsewhere = heldBy(ADA, MONDAY_AFTERNOON);
    Roster roster = draft().with(new Assignment(day, ADA));

    assertThat(roster.violationsUnder(NO_RULES, Map.of(day, utc(MONDAY)), List.of(elsewhere)))
        .containsExactly(
            ConstraintViolation.overlappingWork(
                ADA, day, elsewhere.shift(), Optional.of(elsewhere.roster())));
  }

  /**
   * Work held elsewhere is checked for R3 and nothing more. The working-time rules still judge one
   * roster at a time, so it must not leak into the schedules they are evaluated against. The cap is
   * invented: above one eight-hour shift, below two.
   */
  @Test
  void GivenWorkHeldElsewhere_WhenJudgedUnderAWeeklyCap_ThenOnlyThisRostersWorkCounts() {
    ShiftId day = ShiftId.newId();
    Roster roster = draft().with(new Assignment(day, ADA));
    ConstraintSet tenHoursAWeek =
        NO_RULES.revisedTo(List.of(new MaximumWeeklyWorkingTime(Duration.ofHours(10))));

    assertThat(
            roster.violationsUnder(
                tenHoursAWeek, Map.of(day, utc(MONDAY)), List.of(heldBy(ADA, TUESDAY))))
        .isEmpty();
  }

  // --- Coverage: which shifts are short, and by how many (#205) ----------------------------------

  private static Shift needing(int headcount, TimeRange when) {
    return new Shift(ShiftId.newId(), ACME, when, ZoneOffset.UTC, headcount, Set.of());
  }

  @Test
  void GivenAShiftNeedingThreeWithOneAssigned_WhenCoverageIsCounted_ThenItIsShortByTwo() {
    Shift monday = needing(3, MONDAY);
    Roster roster = draft().with(new Assignment(monday.id(), ADA));

    assertThat(roster.shortfallsAmong(List.of(monday)))
        .singleElement()
        .satisfies(
            shortfall -> {
              assertThat(shortfall.shift()).isEqualTo(monday.id());
              assertThat(shortfall.assigned()).isEqualTo(1);
              assertThat(shortfall.shortBy()).isEqualTo(2);
            });
  }

  /** R22: a headcount is the least a shift needs, so more people than it requires is not short. */
  @Test
  void GivenMorePeopleThanAShiftNeeds_WhenCoverageIsCounted_ThenItIsNotShort() {
    Shift monday = needing(1, MONDAY);
    Roster roster =
        draft()
            .with(new Assignment(monday.id(), ADA))
            .with(new Assignment(monday.id(), EmployeeId.newId()));

    assertThat(roster.shortfallsAmong(List.of(monday))).isEmpty();
  }

  @Test
  void GivenAShiftNobodyIsOn_WhenCoverageIsCounted_ThenItIsShortByItsWholeHeadcount() {
    Shift monday = needing(2, MONDAY);

    assertThat(draft().shortfallsAmong(List.of(monday)))
        .singleElement()
        .extracting(Shortfall::shortBy)
        .isEqualTo(2);
  }

  /** Coverage is the roster's own period's; a shift outside it is not this roster's to fill. */
  @Test
  void GivenAShiftOutsideTheRostersPeriod_WhenCoverageIsCounted_ThenItIsIgnored() {
    Shift nextWeek =
        needing(
            1,
            new TimeRange(
                Instant.parse("2026-09-15T08:00:00Z"), Instant.parse("2026-09-15T16:00:00Z")));

    assertThat(draft().shortfallsAmong(List.of(nextWeek))).isEmpty();
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

    assertThat(published(WEEK).superseded(SUPERSEDED_AT).staysAuthoritativeBeside(replacement))
        .isFalse();
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
    Roster theirs =
        new Roster(RosterId.newId(), OrganisationId.newId(), WEEK).publishedUnder(1, PUBLISHED_AT);

    assertThat(theirs.isSupersededBy(new Roster(RosterId.newId(), ACME, OVERLAPPING_WEEK)))
        .isFalse();
    assertThat(theirs.staysAuthoritativeBeside(new Roster(RosterId.newId(), ACME, NEXT_WEEK)))
        .isFalse();
  }
}
