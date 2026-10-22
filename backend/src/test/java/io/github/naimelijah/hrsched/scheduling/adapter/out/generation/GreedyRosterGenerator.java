package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import io.github.naimelijah.hrsched.scheduling.application.port.out.GenerationRequest;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.EmployeeSchedule;
import io.github.naimelijah.hrsched.scheduling.domain.model.HeldWork;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.WorkingPeriod;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills shifts by taking the first eligible person who still fits the rules: the baseline the
 * solver is measured against (ADR-044).
 *
 * <p>It was the product's only generator until CP-SAT replaced it, and it is kept here, not in
 * production, so the benchmark has something honest to beat. It walks shifts in chronological order
 * and, for each, adds candidates who are qualified, available, not busy with work held elsewhere,
 * and whose resulting schedule still satisfies every constraint. It optimises nothing, and being
 * greedy it will sometimes leave a later shift uncoverable by using someone up early.
 *
 * <p>Named without a Test suffix so Surefire does not try to run it.
 */
final class GreedyRosterGenerator implements RosterGenerator {

  @Override
  public List<Assignment> generate(GenerationRequest request) {
    List<Assignment> assignments = new ArrayList<>();
    Map<ShiftId, Shift> byId = new HashMap<>();
    request.shifts().forEach(shift -> byId.put(shift.id(), shift));
    // What the draft already carries is work its people are doing, so it counts toward their rules
    // and toward each shift's headcount from the start.
    Map<EmployeeId, List<WorkingPeriod>> committed = new LinkedHashMap<>();
    Map<ShiftId, Integer> carried = new HashMap<>();
    for (Assignment assignment : request.draft().assignments()) {
      committed
          .computeIfAbsent(assignment.employee(), employee -> new ArrayList<>())
          .add(byId.get(assignment.shift()).workingPeriod());
      carried.merge(assignment.shift(), 1, Integer::sum);
    }

    List<Shift> chronological =
        request.shifts().stream()
            .sorted(Comparator.comparing(shift -> shift.period().start()))
            .toList();

    for (Shift shift : chronological) {
      int stillNeeded =
          Math.max(0, shift.requiredHeadcount() - carried.getOrDefault(shift.id(), 0));
      for (RosterCandidate candidate : request.candidates()) {
        if (stillNeeded == 0) {
          break;
        }
        if (request.draft().assignments().contains(new Assignment(shift.id(), candidate.id()))
            || !candidate.couldCover(shift.period(), shift.requiredQualifications())
            || isBusyElsewhere(request.heldElsewhere(), candidate.id(), shift)) {
          continue;
        }
        List<WorkingPeriod> proposed =
            new ArrayList<>(committed.getOrDefault(candidate.id(), List.of()));
        if (proposed.stream().anyMatch(working -> working.period().overlaps(shift.period()))) {
          continue;
        }
        proposed.add(shift.workingPeriod());
        if (!request
            .constraints()
            .violationsFor(new EmployeeSchedule(candidate.id(), proposed))
            .isEmpty()) {
          continue;
        }
        committed.put(candidate.id(), proposed);
        assignments.add(new Assignment(shift.id(), candidate.id()));
        stillNeeded--;
      }
    }
    return List.copyOf(assignments);
  }

  /**
   * Whether somebody holds a different shift on another authoritative roster that runs during this
   * one (R35). Holding this very shift there is one place to be, not two (ADR-033).
   */
  private static boolean isBusyElsewhere(List<HeldWork> held, EmployeeId employee, Shift shift) {
    return held.stream()
        .anyMatch(
            work ->
                work.employee().equals(employee)
                    && !work.shift().equals(shift.id())
                    && work.period().overlaps(shift.period()));
  }
}
