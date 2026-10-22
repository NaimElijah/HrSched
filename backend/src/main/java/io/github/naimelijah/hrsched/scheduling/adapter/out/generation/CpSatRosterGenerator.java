package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import com.google.ortools.Loader;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import io.github.naimelijah.hrsched.scheduling.application.port.out.GenerationRequest;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Generates a draft with CP-SAT, the constraint solver in Google OR-Tools (ADR-044).
 *
 * <p>Every rule is a constraint of the model {@link RosterModel} states, so whatever the solver
 * answers is legal by construction, and a shift nobody can legally take is left short rather than
 * filled illegally. Within that, it fills as many places as it can.
 *
 * <p>How short it leaves them is the product's own success metric, so it is measured here. This is
 * the only place that knows both what was asked for and what could legally be filled: by the time
 * the roster reaches the service, the difference between an unfilled place and a place nobody
 * needed has been lost. Measuring in the adapter is also what keeps Micrometer out of the hexagon.
 */
@Component
public class CpSatRosterGenerator implements RosterGenerator {

  private static final Logger log = LoggerFactory.getLogger(CpSatRosterGenerator.class);

  private final Duration timeLimit;
  private final DistributionSummary required;
  private final DistributionSummary unfilled;

  /**
   * Loads the solver's native libraries, so a platform they cannot load on fails at startup rather
   * than on a Scheduler's first draft.
   *
   * @param meters where coverage is recorded, one observation per generation
   * @param properties how long each of the solver's searches may run
   */
  public CpSatRosterGenerator(MeterRegistry meters, GenerationProperties properties) {
    Loader.loadNativeLibraries();
    this.timeLimit = properties.timeLimitPerPhase();
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

  /**
   * @throws IllegalStateException if asked inside a transaction, where a solve would hold a pooled
   *     connection for as long as it ran (ADR-045); or if the solver found no roster at all, which
   *     within the time limit means the problem is too large for it, and otherwise is a defect in
   *     the model, because adding nobody is always legal
   */
  @Override
  public List<Assignment> generate(GenerationRequest request) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Generation runs outside any transaction, so that it holds no connection (ADR-045)");
    }
    RosterModel roster = RosterModel.of(request);
    List<Assignment> added = List.of();
    CpSolverStatus coverage = CpSolverStatus.OPTIMAL;
    double seconds = 0;
    if (!roster.choices().isEmpty()) {
      roster.model().maximize(roster.coverage());
      CpSolver solver = solver();
      coverage = solver.solve(roster.model());
      requireARoster(coverage, roster);
      added = roster.added(solver);
      seconds = solver.wallTime();
    }

    int places = request.shifts().stream().mapToInt(Shift::requiredHeadcount).sum();
    int gaps = gapsLeft(request, added);
    // Recorded even when both are zero: a generation that was asked for nothing is a different
    // event from no generation at all, and a metric that only moves on failure cannot show
    // recovery.
    required.record(places);
    unfilled.record(gaps);
    log.atInfo()
        .addKeyValue("organisation", request.draft().organisation().value())
        .addKeyValue("shifts", request.shifts().size())
        .addKeyValue("candidates", request.candidates().size())
        .addKeyValue("choices", roster.choices().size())
        .addKeyValue("coverageStatus", coverage)
        .addKeyValue("solveSeconds", seconds)
        .addKeyValue("places", places)
        .addKeyValue("uncovered", gaps)
        .log("Roster generated");
    return added;
  }

  private CpSolver solver() {
    CpSolver solver = new CpSolver();
    solver.getParameters().setMaxTimeInSeconds(timeLimit.toNanos() / 1e9);
    return solver;
  }

  private void requireARoster(CpSolverStatus status, RosterModel roster) {
    switch (status) {
      case OPTIMAL, FEASIBLE -> {}
      case UNKNOWN ->
          throw new IllegalStateException(
              "The solver found no roster within %s".formatted(timeLimit));
      case MODEL_INVALID ->
          throw new IllegalStateException(
              "The roster model is invalid: " + roster.model().validate());
      case INFEASIBLE, UNRECOGNIZED ->
          throw new IllegalStateException(
              "The solver answered %s, though adding nobody is always legal: the model does not say what the rules say"
                  .formatted(status));
    }
  }

  /** The places still empty once the draft's own people and the added ones are counted. */
  private static int gapsLeft(GenerationRequest request, List<Assignment> added) {
    Map<ShiftId, Integer> staffed = new HashMap<>();
    Stream.concat(request.draft().assignments().stream(), added.stream())
        .forEach(assignment -> staffed.merge(assignment.shift(), 1, Integer::sum));
    return request.shifts().stream()
        .mapToInt(
            shift -> Math.max(0, shift.requiredHeadcount() - staffed.getOrDefault(shift.id(), 0)))
        .sum();
  }
}
