package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What each objective counts (ADR-010), one edge at a time.
 *
 * <p>The shifts, spans and rosters here are invented to make a count come out one way or the other.
 * A count says nothing about whether a roster is legal; {@code RosterTest} covers that.
 */
class RosterMeasuresTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final TimeRange THE_WEEK = span("2026-09-07T00:00:00Z", "2026-09-14T00:00:00Z");
  private static final TimeRange NEXT_WEEK = span("2026-09-14T00:00:00Z", "2026-09-21T00:00:00Z");
  private static final Instant PUBLISHED = Instant.parse("2026-09-01T09:00:00Z");

  private static final EmployeeId ADA = EmployeeId.newId();
  private static final EmployeeId BEA = EmployeeId.newId();

  private static final Shift MONDAY = shift("2026-09-07T08:00:00Z", "2026-09-07T16:00:00Z", false);
  private static final Shift MONDAY_NIGHT =
      shift("2026-09-07T22:00:00Z", "2026-09-08T06:00:00Z", true);
  private static final Shift TUESDAY_NIGHT =
      shift("2026-09-08T22:00:00Z", "2026-09-09T06:00:00Z", true);
  private static final Shift WEDNESDAY_NIGHT =
      shift("2026-09-09T22:00:00Z", "2026-09-10T06:00:00Z", true);
  private static final Shift NEXT_MONDAY =
      shift("2026-09-14T08:00:00Z", "2026-09-14T16:00:00Z", false);

  private static TimeRange span(String start, String end) {
    return new TimeRange(Instant.parse(start), Instant.parse(end));
  }

  private static Shift shift(String start, String end, boolean unpopular) {
    return new Shift(
        ShiftId.newId(), ACME, span(start, end), ZoneOffset.UTC, 1, Set.of(), unpopular);
  }

  private static Map<ShiftId, Shift> shifts(Shift... shifts) {
    Map<ShiftId, Shift> byId = new LinkedHashMap<>();
    Arrays.stream(shifts).forEach(shift -> byId.put(shift.id(), shift));
    return byId;
  }

  private static final Map<ShiftId, Shift> EVERY_SHIFT =
      shifts(MONDAY, MONDAY_NIGHT, TUESDAY_NIGHT, WEDNESDAY_NIGHT, NEXT_MONDAY);

  private static Assignment on(Shift shift, EmployeeId employee) {
    return new Assignment(shift.id(), employee);
  }

  private static Roster draft(TimeRange period, Assignment... assignments) {
    return new Roster(RosterId.newId(), ACME, period).withAssignments(List.of(assignments));
  }

  private static Roster published(TimeRange period, Assignment... assignments) {
    return draft(period, assignments).publishedUnder(1, PUBLISHED);
  }

  private static RosterMeasures measure(Roster roster, Roster... others) {
    return RosterMeasures.of(roster, EVERY_SHIFT, Map.of(), List.of(others));
  }

  private static RosterMeasures measure(
      Roster roster, Map<EmployeeId, StatedPreferences> preferences) {
    return RosterMeasures.of(roster, EVERY_SHIFT, preferences, List.of());
  }

  // --- Fairness: the heaviest unpopular load ---------------------------------------------------

  @Test
  void GivenNoShiftMarkedUnpopular_WhenMeasured_ThenNobodyCarriesAnUnpopularLoad() {
    assertThat(measure(draft(THE_WEEK, on(MONDAY, ADA))).heaviestUnpopularLoad()).isZero();
  }

  /**
   * The most anybody carries, not the total: two nights for Ada and one for Bea is a load of two.
   */
  @Test
  void GivenUnpopularShiftsSpreadUnevenly_WhenMeasured_ThenTheHeaviestLoadIsCounted() {
    Roster roster =
        draft(
            THE_WEEK,
            on(MONDAY_NIGHT, ADA),
            on(TUESDAY_NIGHT, ADA),
            on(WEDNESDAY_NIGHT, BEA),
            on(MONDAY, BEA));

    assertThat(measure(roster).heaviestUnpopularLoad()).isEqualTo(2);
  }

  @Test
  void GivenTheSameUnpopularShiftsSpreadEvenly_WhenMeasured_ThenTheLoadIsLighter() {
    Roster uneven = draft(THE_WEEK, on(MONDAY_NIGHT, ADA), on(TUESDAY_NIGHT, ADA));
    Roster even = draft(THE_WEEK, on(MONDAY_NIGHT, ADA), on(TUESDAY_NIGHT, BEA));

    assertThat(measure(even).heaviestUnpopularLoad())
        .isLessThan(measure(uneven).heaviestUnpopularLoad());
  }

  // --- Preferences: the ones a roster goes against ---------------------------------------------

  @Test
  void GivenSomebodyOnAShiftTheyWouldRatherNotWork_WhenMeasured_ThenItCountsOnce() {
    Roster roster = draft(THE_WEEK, on(MONDAY_NIGHT, ADA));
    StatedPreferences nightsOff =
        new StatedPreferences(
            List.of(),
            List.of(MONDAY_NIGHT.period(), span("2026-09-07T20:00:00Z", "2026-09-08T08:00:00Z")));

    assertThat(measure(roster, Map.of(ADA, nightsOff)).preferencesGoneAgainst())
        .describedAs("one assignment inside two spans they would rather not work is one")
        .isEqualTo(1);
  }

  /**
   * Per assignment, as ADR-010 says: two nights inside one span they would rather not work is two.
   */
  @Test
  void GivenTwoAssignmentsInsideOneSpanTheyWouldRatherNotWork_WhenMeasured_ThenEachCounts() {
    Roster roster = draft(THE_WEEK, on(MONDAY_NIGHT, ADA), on(TUESDAY_NIGHT, ADA));
    StatedPreferences theseNights =
        new StatedPreferences(
            List.of(), List.of(span("2026-09-07T20:00:00Z", "2026-09-09T08:00:00Z")));

    assertThat(measure(roster, Map.of(ADA, theseNights)).preferencesGoneAgainst()).isEqualTo(2);
  }

  @Test
  void GivenSomebodyOnAShiftOutsideWhatTheyWouldRatherNotWork_WhenMeasured_ThenNothingCounts() {
    Roster roster = draft(THE_WEEK, on(MONDAY, ADA));
    StatedPreferences nightsOff = new StatedPreferences(List.of(), List.of(MONDAY_NIGHT.period()));

    assertThat(measure(roster, Map.of(ADA, nightsOff)).preferencesGoneAgainst()).isZero();
  }

  @Test
  void GivenAWishToWorkThatTheRosterMeets_WhenMeasured_ThenNothingCounts() {
    Roster roster = draft(THE_WEEK, on(MONDAY, ADA));
    StatedPreferences mondays = new StatedPreferences(List.of(MONDAY.period()), List.of());

    assertThat(measure(roster, Map.of(ADA, mondays)).preferencesGoneAgainst()).isZero();
  }

  /** Somebody given no work at all is still somebody whose wish went unmet. */
  @Test
  void GivenAWishToWorkThatTheRosterDoesNotMeet_WhenMeasured_ThenItCountsOnce() {
    Roster roster = draft(THE_WEEK, on(MONDAY, BEA));
    StatedPreferences mondays = new StatedPreferences(List.of(MONDAY.period()), List.of());

    assertThat(measure(roster, Map.of(ADA, mondays)).preferencesGoneAgainst()).isEqualTo(1);
  }

  /** Per wish, as ADR-010 says: two separate wishes the roster meets neither of is two. */
  @Test
  void GivenTwoUnmetWishesFromOnePerson_WhenMeasured_ThenEachCounts() {
    Roster roster = draft(THE_WEEK, on(MONDAY, BEA), on(MONDAY_NIGHT, BEA));
    StatedPreferences mondayAndItsNight =
        new StatedPreferences(List.of(MONDAY.period(), MONDAY_NIGHT.period()), List.of());

    assertThat(measure(roster, Map.of(ADA, mondayAndItsNight)).preferencesGoneAgainst())
        .isEqualTo(2);
  }

  /** A wish for next week is next week's roster's to meet, not this one's. */
  @Test
  void GivenAWishOutsideTheRostersPeriod_WhenMeasured_ThenNothingCounts() {
    Roster roster = draft(THE_WEEK);
    StatedPreferences nextMonday = new StatedPreferences(List.of(NEXT_MONDAY.period()), List.of());

    assertThat(measure(roster, Map.of(ADA, nextMonday)).preferencesGoneAgainst()).isZero();
  }

  /** Two people on a shift one of them wished for meets that wish once, and only that one. */
  @Test
  void GivenOneWishMetTwice_WhenMeasured_ThenItIsStillOneWishMet() {
    Roster roster = draft(THE_WEEK, on(MONDAY, ADA), on(MONDAY_NIGHT, ADA));
    StatedPreferences theWholeMonday =
        new StatedPreferences(
            List.of(span("2026-09-07T00:00:00Z", "2026-09-08T08:00:00Z")), List.of());

    assertThat(measure(roster, Map.of(ADA, theWholeMonday)).preferencesGoneAgainst()).isZero();
  }

  @Test
  void GivenSomebodyTheDirectoryDoesNotName_WhenMeasured_ThenTheyCountAsHavingStatedNothing() {
    Roster roster = draft(THE_WEEK, on(MONDAY_NIGHT, ADA));

    assertThat(measure(roster, Map.of()).preferencesGoneAgainst()).isZero();
  }

  // --- Disruption: the assignments changed from what it supersedes -----------------------------

  @Test
  void GivenNothingPublishedToSupersede_WhenMeasured_ThenNothingHasChanged() {
    assertThat(measure(draft(THE_WEEK, on(MONDAY, ADA))).assignmentsChanged()).isZero();
  }

  @Test
  void GivenADraftKeepingEveryAssignment_WhenMeasured_ThenNothingHasChanged() {
    Roster before = published(THE_WEEK, on(MONDAY, ADA), on(MONDAY_NIGHT, BEA));
    Roster after = draft(THE_WEEK, on(MONDAY, ADA), on(MONDAY_NIGHT, BEA));

    assertThat(measure(after, before).assignmentsChanged()).isZero();
  }

  /** Ada taken off and Bea put on: two people's weeks changed, so two. */
  @Test
  void GivenOneShiftHandedToSomebodyElse_WhenMeasured_ThenTwoAssignmentsHaveChanged() {
    Roster before = published(THE_WEEK, on(MONDAY, ADA));
    Roster after = draft(THE_WEEK, on(MONDAY, BEA));

    assertThat(measure(after, before).assignmentsChanged()).isEqualTo(2);
  }

  /**
   * Superseding is whole (Q16): what the old roster carried beyond the new one's period is lost.
   */
  @Test
  void GivenAnAssignmentBeyondTheDraftsPeriod_WhenSuperseded_ThenItCountsAsDropped() {
    Roster before =
        published(span("2026-09-07T00:00:00Z", "2026-09-21T00:00:00Z"), on(NEXT_MONDAY, ADA));
    Roster after = draft(THE_WEEK);

    assertThat(measure(after, before).assignmentsChanged()).isEqualTo(1);
  }

  /**
   * A draft overlapping two published weeks supersedes both (R11), so what either carried and the
   * draft drops counts, and so does what it adds within either week.
   */
  @Test
  void GivenADraftSupersedingTwoPublishedRosters_WhenMeasured_ThenChangesAgainstBothCount() {
    Roster thisWeek = published(THE_WEEK, on(MONDAY, ADA));
    Roster nextWeek = published(NEXT_WEEK, on(NEXT_MONDAY, ADA));
    Roster after =
        draft(
            span("2026-09-07T00:00:00Z", "2026-09-21T00:00:00Z"),
            on(MONDAY, BEA),
            on(NEXT_MONDAY, BEA));

    assertThat(measure(after, thisWeek, nextWeek).assignmentsChanged())
        .describedAs("Ada loses both Mondays, and Bea gains both")
        .isEqualTo(4);
  }

  /** A shift nobody was rostered around told nobody anything, so filling it changes nothing. */
  @Test
  void GivenAnAdditionOutsideEverySupersededPeriod_WhenMeasured_ThenItDoesNotCount() {
    Roster before =
        published(span("2026-09-07T00:00:00Z", "2026-09-08T00:00:00Z"), on(MONDAY, ADA));
    Roster after = draft(THE_WEEK, on(MONDAY, ADA), on(TUESDAY_NIGHT, BEA));

    assertThat(measure(after, before).assignmentsChanged()).isZero();
  }

  /** Only a published roster it overlaps is superseded (R11); another draft is nobody's week. */
  @Test
  void GivenAnotherDraftOrARosterItDoesNotOverlap_WhenMeasured_ThenNeitherIsABaseline() {
    Roster otherDraft = draft(THE_WEEK, on(MONDAY, ADA));
    Roster nextWeek = published(NEXT_WEEK, on(NEXT_MONDAY, ADA));
    Roster after = draft(THE_WEEK, on(MONDAY, BEA));

    assertThat(measure(after, otherDraft, nextWeek).assignmentsChanged()).isZero();
  }

  // --- What any measure needs ------------------------------------------------------------------

  @Test
  void GivenAnAssignedShiftThatCannotBeResolved_WhenMeasured_ThenItIsRefused() {
    Roster roster = draft(THE_WEEK, on(MONDAY, ADA));

    assertThatThrownBy(() -> RosterMeasures.of(roster, Map.of(), Map.of(), List.of()))
        .isInstanceOf(RosterNotVerifiableException.class);
  }

  @Test
  void GivenAMeasureBelowZero_WhenConstructed_ThenItIsRejected() {
    assertThatThrownBy(() -> new RosterMeasures(0, -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void GivenMeasures_WhenAskedPerObjective_ThenEachAnswersItsOwn() {
    RosterMeasures measures = new RosterMeasures(1, 2, 3);

    assertThat(measures.measureOf(Objective.FAIRNESS)).isEqualTo(1);
    assertThat(measures.measureOf(Objective.PREFERENCES)).isEqualTo(2);
    assertThat(measures.measureOf(Objective.DISRUPTION)).isEqualTo(3);
  }
}
