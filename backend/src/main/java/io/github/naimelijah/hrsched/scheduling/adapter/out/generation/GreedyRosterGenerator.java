package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.EmployeeSchedule;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Fills shifts by taking the first eligible person who still fits the rules.
 *
 * <p>NOT THE PRODUCT. Automatic generation is the point of HrSched and a real implementation is a
 * constraint solver. This exists to prove the {@link RosterGenerator} boundary and to make the rest
 * of the system runnable and testable before that dependency is chosen.
 *
 * <p>What it does: walks shifts in chronological order and, for each, adds candidates who are
 * qualified, available, and whose resulting schedule still satisfies every constraint. What it does
 * not do: optimise anything. It ignores fairness, preferences and disruption entirely — the three
 * objectives that make a roster good rather than merely legal — and being greedy it will sometimes
 * leave a later shift uncoverable by using someone up early.
 *
 * <p>An uncoverable shift is left short rather than filled illegally. A roster that is honest about
 * a gap is more useful than one that hides it.
 */
@Component
public class GreedyRosterGenerator implements RosterGenerator {

  @Override
  public List<Assignment> generate(
      List<Shift> shifts, List<RosterCandidate> candidates, ConstraintSet constraints) {
    List<Assignment> assignments = new ArrayList<>();
    Map<EmployeeId, List<TimeRange>> committed = new LinkedHashMap<>();

    List<Shift> chronological =
        shifts.stream().sorted(Comparator.comparing(shift -> shift.period().start())).toList();

    for (Shift shift : chronological) {
      int stillNeeded = shift.requiredHeadcount();
      for (RosterCandidate candidate : candidates) {
        if (stillNeeded == 0) {
          break;
        }
        if (!candidate.couldCover(shift.period(), shift.requiredQualifications())) {
          continue;
        }
        List<TimeRange> proposed =
            new ArrayList<>(committed.getOrDefault(candidate.id(), List.of()));
        if (proposed.stream().anyMatch(shift.period()::overlaps)) {
          continue;
        }
        proposed.add(shift.period());
        if (!constraints.violationsFor(new EmployeeSchedule(candidate.id(), proposed)).isEmpty()) {
          continue;
        }
        committed.put(candidate.id(), proposed);
        assignments.add(new Assignment(shift.id(), candidate.id()));
        stillNeeded--;
      }
    }
    return List.copyOf(assignments);
  }
}
