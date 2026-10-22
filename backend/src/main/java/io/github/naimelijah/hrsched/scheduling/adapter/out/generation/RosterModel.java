package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.IntervalVar;
import com.google.ortools.sat.LinearArgument;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import io.github.naimelijah.hrsched.scheduling.application.port.out.GenerationRequest;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumConsecutiveWorkingDays;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumDailyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.MaximumWeeklyWorkingTime;
import io.github.naimelijah.hrsched.scheduling.domain.model.MinimumRestBetweenAssignments;
import io.github.naimelijah.hrsched.scheduling.domain.model.Objective;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.StatedPreferences;
import io.github.naimelijah.hrsched.scheduling.domain.model.WorkingTimeConstraint;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;

/**
 * A generation request as a CP-SAT model: one boolean for each person who could take each shift,
 * and every rule as a constraint over those booleans (ADR-044).
 *
 * <p>Java decides everything about time and eligibility, on constant data, with the domain's own
 * methods: who could cover a shift ({@link RosterCandidate#couldCover}), who is busy with work held
 * elsewhere (R35), and how a shift's time falls on the days of its own zone ({@link
 * io.github.naimelijah.hrsched.scheduling.domain.model.WorkingPeriod#timePerDay}). The solver sees
 * only which pairs exist and exact integer sums over them, which keeps each encoding a direct
 * restatement of the rule it encodes.
 *
 * <p>People already on the draft are fixed on it: they are not variables, but their work counts
 * toward their own rules and their shift's headcount, as it does when the finished roster is
 * judged.
 */
final class RosterModel {

  private final CpModel model = new CpModel();
  private final GenerationRequest request;
  private final Map<ShiftId, Shift> shifts = new LinkedHashMap<>();
  private final Map<ShiftId, SortedMap<LocalDate, Duration>> days = new HashMap<>();
  private final Map<Assignment, BoolVar> pairs = new LinkedHashMap<>();
  private final Map<EmployeeId, List<Shift>> open = new LinkedHashMap<>();
  private final Map<EmployeeId, List<Shift>> carried = new LinkedHashMap<>();
  private final Map<EmployeeId, Map<LocalDate, BoolVar>> workingOn = new HashMap<>();
  private Ticks ticks;

  private RosterModel(GenerationRequest request) {
    this.request = request;
  }

  /**
   * @return the model of {@code request}, with every rule stated and no objective yet
   */
  static RosterModel of(GenerationRequest request) {
    RosterModel roster = new RosterModel(request);
    roster.state();
    return roster;
  }

  private void state() {
    request.shifts().forEach(shift -> shifts.put(shift.id(), shift));
    request.shifts().forEach(shift -> days.put(shift.id(), shift.workingPeriod().timePerDay()));
    ticks = ticksFor();
    for (Assignment assignment : request.draft().assignments()) {
      carried
          .computeIfAbsent(assignment.employee(), employee -> new ArrayList<>())
          .add(shifts.get(assignment.shift()));
    }
    for (Shift shift : request.shifts()) {
      for (RosterCandidate candidate : request.candidates()) {
        Assignment pair = new Assignment(shift.id(), candidate.id());
        if (!request.draft().assignments().contains(pair)
            && candidate.couldCover(shift.period(), shift.requiredQualifications())
            && !isBusyElsewhere(candidate.id(), shift)) {
          pairs.put(pair, model.newBoolVar("x" + pairs.size()));
          open.computeIfAbsent(candidate.id(), employee -> new ArrayList<>()).add(shift);
        }
      }
    }
    stateHeadcounts();
    for (EmployeeId employee : open.keySet()) {
      stateOnePlaceAtATime(employee);
      for (WorkingTimeConstraint rule : request.constraints().constraints()) {
        // No default: a new kind of rule does not compile until it is stated here.
        switch (rule) {
          case MinimumRestBetweenAssignments rest -> stateRest(employee, rest.minimumRest());
          case MaximumDailyWorkingTime daily -> stateDailyCap(employee, daily.maximum());
          case MaximumWeeklyWorkingTime weekly -> stateTotalCap(employee, weekly.maximum());
          case MaximumConsecutiveWorkingDays run -> stateLongestRun(employee, run.maximum());
        }
      }
    }
  }

  /**
   * Every span the model will state in ticks: each shift's start and end from the earliest start,
   * each of its days' shares, and each rule's threshold.
   */
  private Ticks ticksFor() {
    Instant origin =
        request.shifts().stream()
            .map(shift -> shift.period().start())
            .min(Comparator.naturalOrder())
            .orElse(Instant.EPOCH);
    List<Duration> spans = new ArrayList<>();
    for (Shift shift : request.shifts()) {
      spans.add(Duration.between(origin, shift.period().start()));
      spans.add(Duration.between(origin, shift.period().end()));
      spans.addAll(days.get(shift.id()).values());
    }
    for (WorkingTimeConstraint rule : request.constraints().constraints()) {
      spans.add(
          switch (rule) {
            case MinimumRestBetweenAssignments rest -> rest.minimumRest();
            case MaximumDailyWorkingTime daily -> daily.maximum();
            case MaximumWeeklyWorkingTime weekly -> weekly.maximum();
            case MaximumConsecutiveWorkingDays run -> Duration.ZERO;
          });
    }
    return Ticks.dividing(origin, spans);
  }

  /** R35: a different shift held on another authoritative roster, running at the same time. */
  private boolean isBusyElsewhere(EmployeeId employee, Shift shift) {
    return request.heldElsewhere().stream()
        .anyMatch(
            work ->
                work.employee().equals(employee)
                    && !work.shift().equals(shift.id())
                    && work.period().overlaps(shift.period()));
  }

  /** R22 as generation keeps it: up to the headcount, counting whoever was carried over. */
  private void stateHeadcounts() {
    Map<ShiftId, Integer> alreadyOn = new HashMap<>();
    request
        .draft()
        .assignments()
        .forEach(assignment -> alreadyOn.merge(assignment.shift(), 1, Integer::sum));
    Map<ShiftId, List<BoolVar>> byShift = new LinkedHashMap<>();
    pairs.forEach(
        (pair, chosen) ->
            byShift.computeIfAbsent(pair.shift(), shift -> new ArrayList<>()).add(chosen));
    byShift.forEach(
        (shift, chosen) ->
            model.addLessOrEqual(
                LinearExpr.sum(chosen.toArray(BoolVar[]::new)),
                Math.max(
                    0, shifts.get(shift).requiredHeadcount() - alreadyOn.getOrDefault(shift, 0))));
  }

  /** R3: no two pieces of one person's work on this roster at once. Periods are half-open. */
  private void stateOnePlaceAtATime(EmployeeId employee) {
    stateNoOverlap(employee, Duration.ZERO);
  }

  /**
   * Rest at least {@code minimum} between any two pieces of work: each shift, stretched by the rest
   * that must follow it, overlaps no other. A gap of exactly the minimum just touches, which
   * passes, as the rule lets it. With no overlaps, every pair being far enough apart is the same as
   * every pair of neighbours being, which is what the rule checks.
   */
  private void stateRest(EmployeeId employee, Duration minimum) {
    stateNoOverlap(employee, minimum);
  }

  private void stateNoOverlap(EmployeeId employee, Duration after) {
    List<IntervalVar> intervals = new ArrayList<>();
    for (Shift shift : open.get(employee)) {
      long start = ticks.at(shift.period().start());
      intervals.add(
          model.newOptionalFixedSizeIntervalVar(
              LinearExpr.constant(start),
              ticks.at(shift.period().end()) + ticks.of(after) - start,
              pairs.get(new Assignment(shift.id(), employee)),
              "i" + intervals.size()));
    }
    for (Shift shift : carried.getOrDefault(employee, List.of())) {
      long start = ticks.at(shift.period().start());
      intervals.add(
          model.newFixedInterval(
              start,
              ticks.at(shift.period().end()) + ticks.of(after) - start,
              "c" + intervals.size()));
    }
    if (intervals.size() > 1) {
      model.addNoOverlap(intervals);
    }
  }

  /** At most {@code maximum} on each calendar day, counted in each shift's own zone (R36). */
  private void stateDailyCap(EmployeeId employee, Duration maximum) {
    Set<LocalDate> dates = new TreeSet<>();
    open.get(employee).forEach(shift -> dates.addAll(days.get(shift.id()).keySet()));
    for (LocalDate date : dates) {
      LinearExprBuilder worked = LinearExpr.newBuilder();
      long most = 0;
      for (Shift shift : open.get(employee)) {
        Duration share = days.get(shift.id()).get(date);
        if (share != null) {
          worked.addTerm(pairs.get(new Assignment(shift.id(), employee)), ticks.of(share));
          most += ticks.of(share);
        }
      }
      long left = ticks.of(maximum) - carriedOn(employee, date);
      if (most > left) {
        model.addLessOrEqual(worked, left);
      }
    }
  }

  private long carriedOn(EmployeeId employee, LocalDate date) {
    return carried.getOrDefault(employee, List.of()).stream()
        .map(shift -> days.get(shift.id()).get(date))
        .filter(share -> share != null)
        .mapToLong(ticks::of)
        .sum();
  }

  /**
   * At most {@code maximum} across the whole roster. The rule judges one roster's schedule, so work
   * held elsewhere does not count toward it (ADR-033), and a shift straddling the roster's edge
   * counts in full.
   */
  private void stateTotalCap(EmployeeId employee, Duration maximum) {
    LinearExprBuilder worked = LinearExpr.newBuilder();
    long most = 0;
    for (Shift shift : open.get(employee)) {
      long length = ticks.of(shift.period().length());
      worked.addTerm(pairs.get(new Assignment(shift.id(), employee)), length);
      most += length;
    }
    long left =
        ticks.of(maximum)
            - carried.getOrDefault(employee, List.of()).stream()
                .mapToLong(shift -> ticks.of(shift.period().length()))
                .sum();
    if (most > left) {
      model.addLessOrEqual(worked, left);
    }
  }

  /**
   * No run of more than {@code maximum} working days, a working day being one with any time on it.
   * A longer run holds {@code maximum + 1} days in a row, so it is enough that every such window
   * has a day off. A day's literal is only forced on by the shifts touching it; it is never forced
   * off, which leaves exactly the same rosters allowed as if it were their disjunction.
   */
  private void stateLongestRun(EmployeeId employee, int maximum) {
    Set<LocalDate> carriedDates = new TreeSet<>();
    carried
        .getOrDefault(employee, List.of())
        .forEach(shift -> carriedDates.addAll(days.get(shift.id()).keySet()));
    Map<LocalDate, BoolVar> literals =
        workingOn.computeIfAbsent(employee, ignored -> workingDays(employee, carriedDates));
    Set<LocalDate> possible = new TreeSet<>(carriedDates);
    possible.addAll(literals.keySet());
    for (LocalDate first : possible) {
      List<BoolVar> undecided = new ArrayList<>();
      int certain = 0;
      boolean everyDayPossible = true;
      for (int offset = 0; offset <= maximum && everyDayPossible; offset++) {
        LocalDate day = first.plusDays(offset);
        if (carriedDates.contains(day)) {
          certain++;
        } else if (literals.containsKey(day)) {
          undecided.add(literals.get(day));
        } else {
          everyDayPossible = false;
        }
      }
      if (everyDayPossible) {
        model.addLessOrEqual(LinearExpr.sum(undecided.toArray(BoolVar[]::new)), maximum - certain);
      }
    }
  }

  /** One literal per day this person might work and is not already working through carried work. */
  private Map<LocalDate, BoolVar> workingDays(EmployeeId employee, Set<LocalDate> carriedDates) {
    Map<LocalDate, BoolVar> literals = new LinkedHashMap<>();
    for (Shift shift : open.get(employee)) {
      BoolVar chosen = pairs.get(new Assignment(shift.id(), employee));
      for (LocalDate day : days.get(shift.id()).keySet()) {
        if (!carriedDates.contains(day)) {
          BoolVar working =
              literals.computeIfAbsent(day, ignored -> model.newBoolVar("w" + literals.size()));
          model.addImplication(chosen, working);
        }
      }
    }
    return literals;
  }

  /**
   * ADR-010's weighted score of the roster the solver chooses: the sum, over each weighted
   * objective, of its weight times its measure, lower being better.
   *
   * <p>Each measure restates {@code RosterMeasures} over the draft's own people and the solver's
   * choices, with every auxiliary variable pinned exactly, so at any solution its value is what the
   * domain would compute for that roster, not merely at the optimum.
   *
   * @return the score; zero when nothing is weighted
   */
  LinearExpr score() {
    LinearExprBuilder score = LinearExpr.newBuilder();
    for (Objective objective : Objective.values()) {
      request
          .weights()
          .weightOf(objective)
          .ifPresent(weight -> score.addTerm(measureOf(objective), weight));
    }
    return score.build();
  }

  private LinearArgument measureOf(Objective objective) {
    return switch (objective) {
      case FAIRNESS -> heaviestUnpopularLoad();
      case PREFERENCES -> preferencesGoneAgainst();
      case DISRUPTION -> assignmentsChanged();
    };
  }

  /** Fairness: the most unpopular shifts any one person carries, or zero if nobody carries one. */
  private LinearArgument heaviestUnpopularLoad() {
    Map<EmployeeId, LinearExprBuilder> loads = new LinkedHashMap<>();
    for (Assignment assignment : request.draft().assignments()) {
      if (shifts.get(assignment.shift()).unpopular()) {
        loads.computeIfAbsent(assignment.employee(), employee -> LinearExpr.newBuilder()).add(1);
      }
    }
    pairs.forEach(
        (pair, chosen) -> {
          if (shifts.get(pair.shift()).unpopular()) {
            loads.computeIfAbsent(pair.employee(), employee -> LinearExpr.newBuilder()).add(chosen);
          }
        });
    if (loads.isEmpty()) {
      return LinearExpr.constant(0);
    }
    long unpopular = request.shifts().stream().filter(Shift::unpopular).count();
    IntVar heaviest = model.newIntVar(0, unpopular, "fairness");
    List<LinearArgument> each = new ArrayList<>();
    each.add(LinearExpr.constant(0));
    loads.values().forEach(load -> each.add(load.build()));
    model.addMaxEquality(heaviest, each);
    return heaviest;
  }

  /**
   * Preferences: one for each assignment inside a span its assignee would rather not work, and one
   * for each span a candidate would like to work, overlapping the draft's period, that none of
   * their assignments overlaps.
   */
  private LinearArgument preferencesGoneAgainst() {
    Map<EmployeeId, StatedPreferences> stated = new HashMap<>();
    request.candidates().forEach(candidate -> stated.put(candidate.id(), candidate.preferences()));
    LinearExprBuilder against = LinearExpr.newBuilder();
    for (Assignment assignment : request.draft().assignments()) {
      if (ratherNot(stated, assignment)) {
        against.add(1);
      }
    }
    pairs.forEach(
        (pair, chosen) -> {
          if (ratherNot(stated, pair)) {
            against.add(chosen);
          }
        });
    for (RosterCandidate candidate : request.candidates()) {
      for (TimeRange wish : candidate.preferences().wouldLikeToWork()) {
        if (!wish.overlaps(request.draft().period())
            || carried.getOrDefault(candidate.id(), List.of()).stream()
                .anyMatch(shift -> wish.overlaps(shift.period()))) {
          continue;
        }
        BoolVar[] meeting =
            open.getOrDefault(candidate.id(), List.of()).stream()
                .filter(shift -> wish.overlaps(shift.period()))
                .map(shift -> pairs.get(new Assignment(shift.id(), candidate.id())))
                .toArray(BoolVar[]::new);
        against.add(1);
        if (meeting.length > 0) {
          BoolVar met = model.newBoolVar("met");
          model.addMaxEquality(met, meeting);
          against.addTerm(met, -1);
        }
      }
    }
    return against.build();
  }

  private boolean ratherNot(Map<EmployeeId, StatedPreferences> stated, Assignment assignment) {
    TimeRange when = shifts.get(assignment.shift()).period();
    return stated
        .getOrDefault(assignment.employee(), StatedPreferences.none())
        .wouldRatherNotWork()
        .stream()
        .anyMatch(when::overlaps);
  }

  /**
   * Disruption: one for each assignment the superseded rosters carried that the draft drops,
   * wherever its shift lies, and one for each the draft adds on a shift overlapping one of their
   * periods.
   */
  private LinearArgument assignmentsChanged() {
    if (request.superseded().isEmpty()) {
      return LinearExpr.constant(0);
    }
    Set<Assignment> before = new HashSet<>();
    request.superseded().forEach(replaced -> before.addAll(replaced.assignments()));
    Set<Assignment> kept = Set.copyOf(request.draft().assignments());
    LinearExprBuilder changed = LinearExpr.newBuilder();
    for (Assignment assignment : before) {
      if (!kept.contains(assignment)) {
        changed.add(1);
        BoolVar chosen = pairs.get(assignment);
        if (chosen != null) {
          changed.addTerm(chosen, -1);
        }
      }
    }
    for (Assignment assignment : request.draft().assignments()) {
      if (!before.contains(assignment) && liesWhereTheyGoverned(assignment)) {
        changed.add(1);
      }
    }
    pairs.forEach(
        (pair, chosen) -> {
          if (!before.contains(pair) && liesWhereTheyGoverned(pair)) {
            changed.add(chosen);
          }
        });
    return changed.build();
  }

  private boolean liesWhereTheyGoverned(Assignment assignment) {
    TimeRange when = shifts.get(assignment.shift()).period();
    return request.superseded().stream().anyMatch(replaced -> replaced.period().overlaps(when));
  }

  /**
   * Keeps every later solution at least as full as {@code solver}'s, and starts the search from it:
   * the second solve ranks rosters without trading away a place the first one filled.
   */
  void keepCoverageOf(CpSolver solver) {
    LinearExpr filled = coverage();
    model.addGreaterOrEqual(filled, solver.value(filled));
    model.clearHints();
    pairs.values().forEach(chosen -> model.addHint(chosen, solver.booleanValue(chosen)));
  }

  CpModel model() {
    return model;
  }

  /**
   * @return each place somebody could be put, with the variable saying whether they are: what the
   *     solver decides, and what a hint or an assumption would name
   */
  Map<Assignment, BoolVar> choices() {
    return Collections.unmodifiableMap(pairs);
  }

  /**
   * @return the places filled beyond what the draft already carries
   */
  LinearExpr coverage() {
    return LinearExpr.sum(pairs.values().toArray(BoolVar[]::new));
  }

  /**
   * @return the assignments the solver's current solution adds to the draft, in the order of the
   *     request's shifts and then its candidates
   */
  List<Assignment> added(CpSolver solver) {
    List<Assignment> added = new ArrayList<>();
    pairs.forEach(
        (pair, chosen) -> {
          if (solver.booleanValue(chosen)) {
            added.add(pair);
          }
        });
    return List.copyOf(added);
  }
}
