package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.scheduling.application.port.out.GenerationRequest;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.HeldWork;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumConsecutiveWorkingDays;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumWeeklyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.MinimumRestBetweenAssignments;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.StatedPreferences;
import io.github.naimelijah.hrsched.scheduling.domain.model.WorkingPeriod;
import io.github.naimelijah.hrsched.scheduling.domain.model.WorkingTimeConstraint;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What every {@link RosterGenerator} promises, whatever it optimises: never to break a rule, and to
 * leave a shift short rather than fill it illegally (ADR-005).
 *
 * <p>Each implementation's test extends this and supplies the generator, so a replacement is held
 * to exactly what the one before it was. Roster quality is not asserted here, because it is what
 * implementations differ in; only what any of them must do is.
 *
 * <p>Every constraint value below is invented to force a specific outcome and says nothing about
 * any jurisdiction.
 *
 * <p>Named without a Test suffix, and abstract, so Surefire runs it only through its subclasses.
 */
abstract class RosterGeneratorContract {

  static final OrganisationId ACME = OrganisationId.newId();
  static final TimeRange THE_WEEK =
      new TimeRange(Instant.parse("2026-10-05T00:00:00Z"), Instant.parse("2026-10-12T00:00:00Z"));

  /** How many seeded problems the legality property is checked on. */
  private static final int SEEDS = 25;

  /**
   * @return the implementation under test, fresh for each test
   */
  protected abstract RosterGenerator generator();

  static Shift shift(String start, String end, int headcount, Set<QualificationId> needed) {
    return new Shift(
        ShiftId.newId(),
        ACME,
        new TimeRange(Instant.parse(start), Instant.parse(end)),
        ZoneOffset.UTC,
        headcount,
        needed,
        false);
  }

  static RosterCandidate anyone(String name) {
    return new RosterCandidate(
        EmployeeId.newId(), name, Set.of(), List.of(), StatedPreferences.none());
  }

  static ConstraintSet rules(WorkingTimeConstraint... rules) {
    return ConstraintSet.initial(ACME).revisedTo(List.of(rules));
  }

  static Roster emptyDraft() {
    return new Roster(RosterId.newId(), ACME, THE_WEEK);
  }

  /** Generation into an empty draft for {@link #THE_WEEK}, with nothing held elsewhere. */
  static GenerationRequest request(
      List<Shift> shifts, List<RosterCandidate> candidates, ConstraintSet constraints) {
    return new GenerationRequest(emptyDraft(), shifts, candidates, constraints, List.of());
  }

  /** {@code holder}'s copy of {@code shift} on a neighbouring published roster. */
  static HeldWork heldOn(Shift shift, RosterCandidate holder) {
    return new HeldWork(RosterId.newId(), shift.id(), holder.id(), shift.period());
  }

  /**
   * Generation of {@code shift} into a draft already carrying {@code holder} on it, as the use case
   * seeds a draft when a neighbouring roster staffs that shift (#130).
   */
  static GenerationRequest carrying(
      Shift shift,
      RosterCandidate holder,
      List<RosterCandidate> candidates,
      ConstraintSet constraints) {
    return new GenerationRequest(
        emptyDraft().with(new Assignment(shift.id(), holder.id())),
        List.of(shift),
        candidates,
        constraints,
        List.of(heldOn(shift, holder)));
  }

  /**
   * Every way {@code added} breaks what a generator promises for {@code request}, judged by the
   * domain itself rather than by anything a generator shares, so a generator cannot pass by
   * agreeing with its own mistake.
   *
   * @return a description of each breach; empty if the answer keeps every promise
   */
  static List<String> breachesOf(GenerationRequest request, List<Assignment> added) {
    Map<ShiftId, Shift> shifts = new LinkedHashMap<>();
    request.shifts().forEach(shift -> shifts.put(shift.id(), shift));
    Map<EmployeeId, RosterCandidate> candidates = new HashMap<>();
    request.candidates().forEach(candidate -> candidates.put(candidate.id(), candidate));

    List<String> breaches = new ArrayList<>();
    Set<Assignment> seen = new HashSet<>(request.draft().assignments());
    for (Assignment assignment : added) {
      Shift shift = shifts.get(assignment.shift());
      RosterCandidate candidate = candidates.get(assignment.employee());
      if (shift == null) {
        breaches.add("%s names a shift it was not asked to cover".formatted(assignment));
      } else if (candidate == null) {
        breaches.add("%s names somebody who is not a candidate".formatted(assignment));
      } else if (!candidate.couldCover(shift.period(), shift.requiredQualifications())) {
        breaches.add("%s lacks a qualification or was declared away".formatted(assignment));
      }
      if (!seen.add(assignment)) {
        breaches.add("%s is on the draft twice".formatted(assignment));
      }
    }
    if (!breaches.isEmpty()) {
      return breaches;
    }

    Roster filled =
        request
            .draft()
            .withAssignments(
                Stream.concat(request.draft().assignments().stream(), added.stream()).toList());
    Map<ShiftId, Integer> staffed = new HashMap<>();
    Map<ShiftId, Integer> carried = new HashMap<>();
    filled.assignments().forEach(assignment -> staffed.merge(assignment.shift(), 1, Integer::sum));
    request
        .draft()
        .assignments()
        .forEach(assignment -> carried.merge(assignment.shift(), 1, Integer::sum));
    staffed.forEach(
        (id, count) -> {
          int allowed = Math.max(shifts.get(id).requiredHeadcount(), carried.getOrDefault(id, 0));
          if (count > allowed) {
            breaches.add("shift %s staffed %d, above %d".formatted(id.value(), count, allowed));
          }
        });

    Map<ShiftId, WorkingPeriod> periods = new LinkedHashMap<>();
    shifts.forEach((id, shift) -> periods.put(id, shift.workingPeriod()));
    filled
        .violationsUnder(request.constraints(), periods, request.heldElsewhere())
        .forEach(violation -> breaches.add(violation.toString()));
    return breaches;
  }

  @Test
  void GivenNobodyQualified_WhenGenerating_ThenTheShiftIsLeftShortRatherThanFilled() {
    Shift needsATicket =
        shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of(QualificationId.newId()));

    List<Assignment> added =
        generator()
            .generate(
                request(
                    List.of(needsATicket), List.of(anyone("Ada")), ConstraintSet.initial(ACME)));

    assertThat(added).describedAs("a roster honest about a gap beats one that hides it").isEmpty();
  }

  @Test
  void GivenSomebodyWhoDeclaredTheyCannotWorkThen_WhenGenerating_ThenTheShiftIsLeftShort() {
    Shift monday = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of());
    RosterCandidate away =
        new RosterCandidate(
            EmployeeId.newId(),
            "Ada",
            Set.of(),
            List.of(
                new TimeRange(
                    Instant.parse("2026-10-05T12:00:00Z"), Instant.parse("2026-10-05T13:00:00Z"))),
            StatedPreferences.none());

    List<Assignment> added =
        generator().generate(request(List.of(monday), List.of(away), ConstraintSet.initial(ACME)));

    assertThat(added).isEmpty();
  }

  @Test
  void GivenOnePersonAndTwoOverlappingShifts_WhenGenerating_ThenTheyCoverOnlyOne() {
    Shift morning = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of());
    Shift clashing = shift("2026-10-05T12:00:00Z", "2026-10-05T20:00:00Z", 1, Set.of());

    List<Assignment> added =
        generator()
            .generate(
                request(
                    List.of(morning, clashing),
                    List.of(anyone("Ada")),
                    ConstraintSet.initial(ACME)));

    assertThat(added).hasSize(1);
  }

  @Test
  void GivenAConstraintTheAssignmentWouldBreak_WhenGenerating_ThenNobodyIsAssigned() {
    Shift eightHours = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of());
    // Invented: one hour a day, which no eight-hour shift can satisfy.
    ConstraintSet oneHourDays = rules(new MaximumDailyWorkingTime(Duration.ofHours(1)));

    List<Assignment> added =
        generator().generate(request(List.of(eightHours), List.of(anyone("Ada")), oneHourDays));

    assertThat(added).isEmpty();
  }

  /**
   * Generation judges the same rules publication does, so it counts days in each shift's zone too
   * (R36). Both Tokyo shifts fall on the 7th there, twelve hours against an invented cap of ten, so
   * Ada can take only one. Counted in UTC they straddle two days and she would get both.
   */
  @Test
  void GivenTwoShiftsOnOneLocalDay_WhenGeneratingUnderADailyCap_ThenOnlyOneIsAssignedToThem() {
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
    ConstraintSet tenHourDays = rules(new MaximumDailyWorkingTime(Duration.ofHours(10)));

    List<Assignment> added =
        generator()
            .generate(request(List.of(lateNight, morning), List.of(anyone("Ada")), tenHourDays));

    assertThat(added).hasSize(1);
  }

  @Test
  void GivenShiftsCloserThanTheMinimumRest_WhenGenerating_ThenOnePersonTakesOnlyOne() {
    Shift early = shift("2026-10-05T06:00:00Z", "2026-10-05T14:00:00Z", 1, Set.of());
    Shift late = shift("2026-10-05T20:00:00Z", "2026-10-06T04:00:00Z", 1, Set.of());
    // Invented: seven hours' rest, one more than the six between these two shifts.
    ConstraintSet sevenHoursRest = rules(new MinimumRestBetweenAssignments(Duration.ofHours(7)));

    List<Assignment> added =
        generator().generate(request(List.of(early, late), List.of(anyone("Ada")), sevenHoursRest));

    assertThat(added).hasSize(1);
  }

  /** A gap exactly at the minimum is enough rest: the rule refuses only less. */
  @Test
  void GivenShiftsExactlyTheMinimumRestApart_WhenGenerating_ThenOnePersonTakesBoth() {
    Shift early = shift("2026-10-05T06:00:00Z", "2026-10-05T14:00:00Z", 1, Set.of());
    Shift late = shift("2026-10-05T20:00:00Z", "2026-10-06T04:00:00Z", 1, Set.of());
    // Invented: six hours' rest, exactly the gap between these two shifts.
    ConstraintSet sixHoursRest = rules(new MinimumRestBetweenAssignments(Duration.ofHours(6)));

    List<Assignment> added =
        generator().generate(request(List.of(early, late), List.of(anyone("Ada")), sixHoursRest));

    assertThat(added).hasSize(2);
  }

  @Test
  void GivenAWeeklyCap_WhenGenerating_ThenNobodyIsGivenMoreThanIt() {
    Shift monday = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of());
    Shift tuesday = shift("2026-10-06T08:00:00Z", "2026-10-06T16:00:00Z", 1, Set.of());
    Shift wednesday = shift("2026-10-07T08:00:00Z", "2026-10-07T16:00:00Z", 1, Set.of());
    // Invented: twenty hours, room for two eight-hour shifts and not three.
    ConstraintSet twentyHourWeeks = rules(new MaximumWeeklyWorkingTime(Duration.ofHours(20)));

    List<Assignment> added =
        generator()
            .generate(
                request(
                    List.of(monday, tuesday, wednesday), List.of(anyone("Ada")), twentyHourWeeks));

    assertThat(added).hasSize(2);
  }

  @Test
  void GivenAMaximumRunOfDays_WhenGenerating_ThenNobodyWorksALongerOne() {
    Shift monday = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of());
    Shift tuesday = shift("2026-10-06T08:00:00Z", "2026-10-06T16:00:00Z", 1, Set.of());
    Shift wednesday = shift("2026-10-07T08:00:00Z", "2026-10-07T16:00:00Z", 1, Set.of());
    // Invented: two days in a row at most.
    ConstraintSet twoDayRuns = rules(new MaximumConsecutiveWorkingDays(2));

    List<Assignment> added =
        generator()
            .generate(
                request(List.of(monday, tuesday, wednesday), List.of(anyone("Ada")), twoDayRuns));

    assertThat(added).hasSize(2);
  }

  @Test
  void GivenEnoughPeople_WhenGenerating_ThenHeadcountIsMetWithoutRepeatingAnyone() {
    Shift needsTwo = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 2, Set.of());

    List<Assignment> added =
        generator()
            .generate(
                request(
                    List.of(needsTwo),
                    List.of(anyone("Ada"), anyone("Grace"), anyone("Alan")),
                    ConstraintSet.initial(ACME)));

    assertThat(added).hasSize(2);
    assertThat(added.stream().map(Assignment::employee).distinct()).hasSize(2);
  }

  /**
   * R35: a different shift held on another authoritative roster makes its holder busy while it
   * runs, however free this draft leaves them.
   */
  @Test
  void GivenWorkHeldElsewhereOverlappingAShift_WhenGenerating_ThenItsHolderIsNotPutOnIt() {
    Shift monday = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of());
    RosterCandidate ada = anyone("Ada");
    HeldWork evening =
        new HeldWork(
            RosterId.newId(),
            ShiftId.newId(),
            ada.id(),
            new TimeRange(
                Instant.parse("2026-10-05T15:00:00Z"), Instant.parse("2026-10-05T23:00:00Z")));

    List<Assignment> added =
        generator()
            .generate(
                new GenerationRequest(
                    emptyDraft(),
                    List.of(monday),
                    List.of(ada),
                    ConstraintSet.initial(ACME),
                    List.of(evening)));

    assertThat(added).isEmpty();
  }

  /** #130: people carried over count toward the headcount, and stay; only the rest is filled. */
  @Test
  void GivenADraftAlreadyCarryingSomebody_WhenGenerating_ThenOnlyThePlacesLeftAreFilled() {
    Shift needsTwo = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 2, Set.of());
    RosterCandidate grace = anyone("Grace");

    List<Assignment> added =
        generator()
            .generate(
                carrying(
                    needsTwo,
                    grace,
                    List.of(anyone("Ada"), grace, anyone("Alan")),
                    ConstraintSet.initial(ACME)));

    assertThat(added).hasSize(1);
    assertThat(added).extracting(Assignment::employee).doesNotContain(grace.id());
  }

  /**
   * People carried over beyond the headcount stay too, because the generator only ever adds, and
   * nobody is added beside them.
   */
  @Test
  void GivenADraftCarryingMoreThanAShiftNeeds_WhenGenerating_ThenNobodyIsAddedToIt() {
    Shift needsOne = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of());
    RosterCandidate grace = anyone("Grace");
    RosterCandidate alan = anyone("Alan");
    Roster overstaffed =
        emptyDraft()
            .withAssignments(
                List.of(
                    new Assignment(needsOne.id(), grace.id()),
                    new Assignment(needsOne.id(), alan.id())));

    List<Assignment> added =
        generator()
            .generate(
                new GenerationRequest(
                    overstaffed,
                    List.of(needsOne),
                    List.of(anyone("Ada"), grace, alan),
                    ConstraintSet.initial(ACME),
                    List.of(heldOn(needsOne, grace), heldOn(needsOne, alan))));

    assertThat(added).isEmpty();
  }

  /** Work carried over is work its holder does in this roster, so their rules count it. */
  @Test
  void GivenCarriedWork_WhenGenerating_ThenItCountsTowardItsHoldersRules() {
    Shift monday = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of());
    Shift tuesday = shift("2026-10-06T08:00:00Z", "2026-10-06T16:00:00Z", 1, Set.of());
    RosterCandidate ada = anyone("Ada");
    // Invented: twelve hours, room for one eight-hour shift and not two.
    ConstraintSet twelveHourWeeks = rules(new MaximumWeeklyWorkingTime(Duration.ofHours(12)));

    GenerationRequest request = carrying(monday, ada, List.of(ada), twelveHourWeeks);
    List<Assignment> added =
        generator()
            .generate(
                new GenerationRequest(
                    request.draft(),
                    List.of(monday, tuesday),
                    request.candidates(),
                    twelveHourWeeks,
                    request.heldElsewhere()));

    assertThat(added).isEmpty();
  }

  @Test
  void GivenNoShiftsAtAll_WhenGenerating_ThenNothingIsAssigned() {
    List<Assignment> added =
        generator()
            .generate(request(List.of(), List.of(anyone("Ada")), ConstraintSet.initial(ACME)));

    assertThat(added).isEmpty();
  }

  /**
   * The promise as a whole, on problems nobody wrote by hand: every rule kind, a duplicated kind,
   * qualifications, absences, and days counted in three zones across a change of the clocks.
   */
  @Test
  void GivenSeededProblems_WhenGenerating_ThenEveryAnswerKeepsEveryPromise() {
    for (long seed = 1; seed <= SEEDS; seed++) {
      GenerationRequest problem = GenerationInstances.instance(seed, 8, 14);

      List<Assignment> added = generator().generate(problem);

      assertThat(breachesOf(problem, added)).describedAs("seed %d", seed).isEmpty();
    }
  }
}
