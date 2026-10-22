package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import io.github.naimelijah.hrsched.scheduling.application.port.out.GenerationRequest;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Seeded, reproducible generation problems: the same seed always builds the same request, down to
 * every identity, so a failure names a seed that reproduces it.
 *
 * <p>Every value here is invented to exercise the generator, and none is taken from any
 * jurisdiction: the rest gaps, daily and weekly caps and runs of days are drawn from ranges chosen
 * only so that some rules bind and some do not. The week spans 2026-10-25, when the clocks in
 * Europe/London go back, so a day there is 25 hours long; Asia/Tokyo puts midnight nine hours ahead
 * of UTC, so the same instant falls on different days by zone (R36).
 *
 * <p>Named without a Test suffix so Surefire does not try to run it.
 */
final class GenerationInstances {

  /** Invented: a week running across the end of British Summer Time in 2026. */
  static final TimeRange WEEK =
      new TimeRange(Instant.parse("2026-10-19T00:00:00Z"), Instant.parse("2026-10-26T00:00:00Z"));

  private static final List<ZoneId> ZONES =
      List.of(ZoneId.of("Europe/London"), ZoneId.of("Asia/Tokyo"), ZoneOffset.UTC);

  private GenerationInstances() {}

  /**
   * One problem.
   *
   * @param seed what every choice is drawn from
   * @param people how many candidates
   * @param shifts how many shifts, each starting on the hour somewhere in {@link #WEEK}
   * @return a request for a draft over {@link #WEEK}, carrying a little work over from a
   *     neighbouring roster, beside some work held there that only makes people busy
   */
  static GenerationRequest instance(long seed, int people, int shifts) {
    Random random = new Random(seed);
    OrganisationId organisation = new OrganisationId(uuid(random));
    List<QualificationId> qualifications =
        List.of(new QualificationId(uuid(random)), new QualificationId(uuid(random)));

    List<Shift> toCover = new ArrayList<>();
    for (int i = 0; i < shifts; i++) {
      Instant start = WEEK.start().plus(Duration.ofHours(random.nextInt(7 * 24)));
      // Invented: four to ten hours long, so some cross midnight and some cross the week's end.
      Instant end = start.plus(Duration.ofHours(4 + random.nextInt(7)));
      toCover.add(
          new Shift(
              new ShiftId(uuid(random)),
              organisation,
              new TimeRange(start, end),
              ZONES.get(random.nextInt(ZONES.size())),
              1 + random.nextInt(3),
              someOf(qualifications, random, 0.3),
              random.nextDouble() < 0.3));
    }

    List<RosterCandidate> candidates = new ArrayList<>();
    for (int i = 0; i < people; i++) {
      candidates.add(
          new RosterCandidate(
              new EmployeeId(uuid(random)),
              "Person " + i,
              someOf(qualifications, random, 0.6),
              maybeASpan(random, 0.3),
              new StatedPreferences(maybeASpan(random, 0.5), maybeASpan(random, 0.5))));
    }

    ConstraintSet constraints = ConstraintSet.initial(organisation).revisedTo(rules(random));
    Roster empty = new Roster(new RosterId(uuid(random)), organisation, WEEK);
    RosterId neighbour = new RosterId(uuid(random));

    // Invented: some people hold a shift on a neighbouring published roster, which only makes them
    // busy while it runs.
    List<HeldWork> held = new ArrayList<>();
    for (RosterCandidate candidate : candidates) {
      if (random.nextDouble() < 0.3) {
        Instant start = WEEK.start().plus(Duration.ofHours(random.nextInt(7 * 24)));
        held.add(
            new HeldWork(
                neighbour,
                new ShiftId(uuid(random)),
                candidate.id(),
                new TimeRange(start, start.plus(Duration.ofHours(4 + random.nextInt(7))))));
      }
    }

    // And some hold one of these very shifts there, so the draft starts with them carried over, as
    // the use case seeds it (#130). Only work that could legally stay is carried, as the use case
    // refuses anything else before a generator is asked.
    Roster seeded = empty;
    for (int attempt = 0; attempt < 3; attempt++) {
      Shift shift = toCover.get(random.nextInt(toCover.size()));
      RosterCandidate candidate = candidates.get(random.nextInt(candidates.size()));
      Assignment carried = new Assignment(shift.id(), candidate.id());
      if (seeded.assignments().contains(carried)
          || !candidate.couldCover(shift.period(), shift.requiredQualifications())) {
        continue;
      }
      List<HeldWork> withIt = new ArrayList<>(held);
      withIt.add(new HeldWork(neighbour, shift.id(), candidate.id(), shift.period()));
      Roster tried = seeded.with(carried);
      if (tried.violationsUnder(constraints, periodsOf(toCover), withIt).isEmpty()) {
        seeded = tried;
        held = withIt;
      }
    }

    return new GenerationRequest(seeded, toCover, candidates, constraints, held);
  }

  private static Map<ShiftId, WorkingPeriod> periodsOf(List<Shift> shifts) {
    Map<ShiftId, WorkingPeriod> periods = new LinkedHashMap<>();
    shifts.forEach(shift -> periods.put(shift.id(), shift.workingPeriod()));
    return periods;
  }

  /**
   * Each kind of rule, some of the time, and sometimes a second of one kind: R24 is not built, so
   * duplicates are stored and every one of them is enforced.
   */
  private static List<WorkingTimeConstraint> rules(Random random) {
    List<WorkingTimeConstraint> rules = new ArrayList<>();
    if (random.nextBoolean()) {
      rules.add(new MinimumRestBetweenAssignments(Duration.ofHours(6 + random.nextInt(7))));
    }
    if (random.nextBoolean()) {
      rules.add(new MaximumDailyWorkingTime(Duration.ofHours(8 + random.nextInt(5))));
    }
    if (random.nextBoolean()) {
      rules.add(new MaximumWeeklyWorkingTime(Duration.ofHours(16 + random.nextInt(25))));
    }
    if (random.nextBoolean()) {
      rules.add(new MaximumConsecutiveWorkingDays(2 + random.nextInt(4)));
    }
    if (random.nextDouble() < 0.25) {
      rules.add(new MaximumDailyWorkingTime(Duration.ofHours(6 + random.nextInt(7))));
    }
    return rules;
  }

  private static Set<QualificationId> someOf(
      List<QualificationId> qualifications, Random random, double each) {
    Set<QualificationId> chosen = new HashSet<>();
    for (QualificationId qualification : qualifications) {
      if (random.nextDouble() < each) {
        chosen.add(qualification);
      }
    }
    return chosen;
  }

  /** At most one span, so declared unavailability never overlaps itself (R7). */
  private static List<TimeRange> maybeASpan(Random random, double chance) {
    if (random.nextDouble() >= chance) {
      return List.of();
    }
    Instant start = WEEK.start().plus(Duration.ofHours(random.nextInt(7 * 24)));
    return List.of(new TimeRange(start, start.plus(Duration.ofHours(4 + random.nextInt(21)))));
  }

  private static UUID uuid(Random random) {
    return new UUID(random.nextLong(), random.nextLong());
  }
}
