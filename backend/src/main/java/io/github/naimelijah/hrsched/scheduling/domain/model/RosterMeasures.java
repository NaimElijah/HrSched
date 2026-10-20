package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How a roster measures on each objective (ADR-010). Each is a count, and lower is better.
 *
 * <p>A measure judges nothing. A roster measuring well is still refused if it breaks a rule, and
 * one measuring badly is still published if it breaks none.
 *
 * @param heaviestUnpopularLoad the most unpopular shifts any one person is assigned
 * @param preferencesGoneAgainst assignments inside a span the assignee would rather not work, plus
 *     spans somebody would like to work in the roster's period on which they hold no assignment
 * @param assignmentsChanged assignments the roster drops from the published rosters it would
 *     supersede, plus those it adds on a shift within their periods
 */
public record RosterMeasures(
    int heaviestUnpopularLoad, int preferencesGoneAgainst, int assignmentsChanged) {

  public RosterMeasures {
    if (heaviestUnpopularLoad < 0 || preferencesGoneAgainst < 0 || assignmentsChanged < 0) {
      throw new IllegalArgumentException("A measure counts something, so it is never below zero");
    }
  }

  /**
   * Measures a roster.
   *
   * <p>Somebody assigned whom {@code preferences} does not name is taken to have stated nothing.
   * That is only reachable for an assignee the directory no longer lists, which is already a
   * violation (R5) that refuses publication, so nothing published was measured on a guess.
   *
   * @param roster the roster to measure, usually a draft
   * @param shifts the shifts it could assign; must hold every shift it does assign
   * @param preferences what each person said about when they would like to work
   * @param others every other roster of the organisation, from which the published ones this one
   *     would supersede are found, by the rule publication uses (R11)
   * @return how it measures on each objective
   * @throws RosterNotVerifiableException if an assigned shift is missing from {@code shifts}: work
   *     that cannot be placed in time cannot be counted
   */
  public static RosterMeasures of(
      Roster roster,
      Map<ShiftId, Shift> shifts,
      Map<EmployeeId, StatedPreferences> preferences,
      Collection<Roster> others) {
    Objects.requireNonNull(roster, "roster must not be null");
    Objects.requireNonNull(shifts, "shifts must not be null");
    Objects.requireNonNull(preferences, "preferences must not be null");
    Objects.requireNonNull(others, "others must not be null");
    for (Assignment assignment : roster.assignments()) {
      if (!shifts.containsKey(assignment.shift())) {
        throw new RosterNotVerifiableException(roster.id(), assignment.shift());
      }
    }
    return new RosterMeasures(
        heaviestUnpopularLoad(roster, shifts),
        preferencesGoneAgainst(roster, shifts, preferences),
        assignmentsChanged(roster, shifts, others));
  }

  /**
   * @param objective an objective
   * @return what this roster measures on it
   */
  public int measureOf(Objective objective) {
    return switch (Objects.requireNonNull(objective, "objective must not be null")) {
      case FAIRNESS -> heaviestUnpopularLoad;
      case PREFERENCES -> preferencesGoneAgainst;
      case DISRUPTION -> assignmentsChanged;
    };
  }

  private static int heaviestUnpopularLoad(Roster roster, Map<ShiftId, Shift> shifts) {
    Map<EmployeeId, Integer> carried = new HashMap<>();
    for (Assignment assignment : roster.assignments()) {
      if (shifts.get(assignment.shift()).unpopular()) {
        carried.merge(assignment.employee(), 1, Integer::sum);
      }
    }
    return carried.values().stream().mapToInt(Integer::intValue).max().orElse(0);
  }

  /**
   * A span somebody would rather not work counts once for each assignment of theirs inside it, and
   * a span they would like to work counts once if they hold nothing in it. Neither can be lowered
   * more than once per statement by putting more people on a shift than it needs.
   */
  private static int preferencesGoneAgainst(
      Roster roster, Map<ShiftId, Shift> shifts, Map<EmployeeId, StatedPreferences> preferences) {
    Map<EmployeeId, List<TimeRange>> work =
        roster.assignments().stream()
            .collect(
                Collectors.groupingBy(
                    Assignment::employee,
                    Collectors.mapping(
                        assignment -> shifts.get(assignment.shift()).period(),
                        Collectors.toList())));
    int against = 0;
    for (Assignment assignment : roster.assignments()) {
      TimeRange when = shifts.get(assignment.shift()).period();
      StatedPreferences theirs =
          preferences.getOrDefault(assignment.employee(), StatedPreferences.none());
      if (theirs.wouldRatherNotWork().stream().anyMatch(when::overlaps)) {
        against++;
      }
    }
    for (Map.Entry<EmployeeId, StatedPreferences> person : preferences.entrySet()) {
      List<TimeRange> theirWork = work.getOrDefault(person.getKey(), List.of());
      for (TimeRange wish : person.getValue().wouldLikeToWork()) {
        if (wish.overlaps(roster.period()) && theirWork.stream().noneMatch(wish::overlaps)) {
          against++;
        }
      }
    }
    return against;
  }

  /**
   * Superseding is whole (Q16), so an assignment a superseded roster carried outside this one's
   * period is dropped as surely as one inside it, and counts. One this roster adds counts only on a
   * shift the superseded rosters could have carried, overlapping one of their periods: a shift
   * nobody was rostered around changes nothing anybody was told.
   */
  private static int assignmentsChanged(
      Roster roster, Map<ShiftId, Shift> shifts, Collection<Roster> others) {
    List<Roster> superseded =
        others.stream().filter(other -> other.isSupersededBy(roster)).toList();
    if (superseded.isEmpty()) {
      return 0;
    }
    Set<Assignment> before =
        superseded.stream()
            .flatMap(other -> other.assignments().stream())
            .collect(Collectors.toSet());
    Set<Assignment> after = new HashSet<>(roster.assignments());
    long dropped = before.stream().filter(assignment -> !after.contains(assignment)).count();
    long added =
        after.stream()
            .filter(assignment -> !before.contains(assignment))
            .filter(
                assignment ->
                    superseded.stream()
                        .anyMatch(
                            other ->
                                other.period().overlaps(shifts.get(assignment.shift()).period())))
            .count();
    return Math.toIntExact(dropped + added);
  }
}
