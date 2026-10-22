package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import io.github.naimelijah.hrsched.scheduling.application.port.out.GenerationRequest;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.EmployeeSchedule;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.WorkingPeriod;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
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
 *
 * <p>How short it leaves them is the product's own success metric, so it is measured here. This is
 * the only place that knows both what was asked for and what could legally be filled: by the time
 * the roster reaches the service, the difference between an unfilled place and a place nobody
 * needed has been lost. Measuring in the adapter is also what keeps Micrometer out of the hexagon.
 */
@Component
public class GreedyRosterGenerator implements RosterGenerator {

  private final DistributionSummary required;
  private final DistributionSummary unfilled;

  /**
   * @param meters where coverage is recorded, one observation per generation
   */
  public GreedyRosterGenerator(MeterRegistry meters) {
    this.required =
        DistributionSummary.builder("hrsched.shifts.required")
            .description("Places one generation was asked to fill")
            .baseUnit("places")
            .register(meters);
    this.unfilled =
        DistributionSummary.builder("hrsched.shifts.uncovered")
            .description("Places one generation could not fill without breaking a rule")
            .baseUnit("places")
            .register(meters);
  }

  @Override
  public List<Assignment> generate(GenerationRequest request) {
    List<Assignment> assignments = new ArrayList<>();
    Map<EmployeeId, List<WorkingPeriod>> committed = new LinkedHashMap<>();

    List<Shift> chronological =
        request.shifts().stream()
            .sorted(Comparator.comparing(shift -> shift.period().start()))
            .toList();

    int places = 0;
    int gaps = 0;
    for (Shift shift : chronological) {
      int stillNeeded = shift.requiredHeadcount();
      places += stillNeeded;
      for (RosterCandidate candidate : request.candidates()) {
        if (stillNeeded == 0) {
          break;
        }
        if (!candidate.couldCover(shift.period(), shift.requiredQualifications())) {
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
      gaps += stillNeeded;
    }

    // Recorded even when both are zero: a generation that was asked for nothing is a different
    // event from no generation at all, and a metric that only moves on failure cannot show
    // recovery.
    required.record(places);
    unfilled.record(gaps);
    return List.copyOf(assignments);
  }
}
