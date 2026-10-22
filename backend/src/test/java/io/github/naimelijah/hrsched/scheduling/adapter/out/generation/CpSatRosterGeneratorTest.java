package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import io.github.naimelijah.hrsched.scheduling.application.port.out.GenerationRequest;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.StatedPreferences;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Specification of the CP-SAT generator (ADR-044): every promise in {@link
 * RosterGeneratorContract}, the coverage it is measured by, and what it does that greedy cannot.
 *
 * <p>The model is checked from both sides against the domain, on seeded problems: the contract
 * checks that the domain accepts whatever the model answers, and this checks that the model accepts
 * whatever legal roster the domain does, so it is neither looser nor stricter than the rules.
 *
 * <p>Every constraint value below is invented to force a specific outcome and says nothing about
 * any jurisdiction.
 */
class CpSatRosterGeneratorTest extends RosterGeneratorContract {

  /** Invented: ample for every problem here to be solved to proven optimality. */
  private static final Duration LIMIT = Duration.ofSeconds(10);

  /** How many seeded problems each property is checked on. */
  private static final int SEEDS = 25;

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final CpSatRosterGenerator generator =
      new CpSatRosterGenerator(meters, new GenerationProperties(LIMIT));

  @Override
  protected RosterGenerator generator() {
    return generator;
  }

  private DistributionSummary summary(String name) {
    return meters.get(name).summary();
  }

  /**
   * Greedy takes Ada for the morning, the first who fits, and then nobody else holds the ticket the
   * overlapping afternoon needs. Grace can take the morning instead, so both are covered.
   */
  @Test
  void GivenSomebodyGreedyUsesUpEarly_WhenGenerating_ThenTheShiftOnlyTheyCouldTakeIsCoveredToo() {
    QualificationId ticket = QualificationId.newId();
    Shift morning = shift("2026-10-05T08:00:00Z", "2026-10-05T16:00:00Z", 1, Set.of());
    Shift afternoon = shift("2026-10-05T12:00:00Z", "2026-10-05T20:00:00Z", 1, Set.of(ticket));
    RosterCandidate ada =
        new RosterCandidate(
            EmployeeId.newId(), "Ada", Set.of(ticket), List.of(), StatedPreferences.none());
    GenerationRequest problem =
        request(
            List.of(morning, afternoon),
            List.of(ada, anyone("Grace")),
            ConstraintSet.initial(ACME));

    assertThat(new GreedyRosterGenerator().generate(problem)).hasSize(1);
    assertThat(generator.generate(problem))
        .contains(new Assignment(afternoon.id(), ada.id()))
        .hasSize(2);
  }

  /** On problems nobody wrote by hand, it fills at least as many places as greedy does. */
  @Test
  void GivenSeededProblems_WhenGenerating_ThenNoFewerPlacesAreFilledThanGreedyFills() {
    for (long seed = 1; seed <= SEEDS; seed++) {
      GenerationRequest problem = GenerationInstances.instance(seed, 8, 14);

      assertThat(generator.generate(problem).size())
          .describedAs("seed %d", seed)
          .isGreaterThanOrEqualTo(new GreedyRosterGenerator().generate(problem).size());
    }
  }

  /**
   * The model is no stricter than the rules: every roster the domain accepts, the model can be made
   * to answer. Greedy over candidates in shuffled orders gives many different legal rosters, and
   * each is fixed in the model and solved.
   */
  @Test
  void GivenRostersTheDomainAccepts_WhenFixedInTheModel_ThenTheModelAcceptsThemToo() {
    for (long seed = 1; seed <= SEEDS; seed++) {
      GenerationRequest problem = GenerationInstances.instance(seed, 8, 14);
      for (int order = 0; order < 3; order++) {
        List<RosterCandidate> shuffled = new ArrayList<>(problem.candidates());
        Collections.shuffle(shuffled, new Random(seed * 31 + order));
        List<Assignment> legal =
            new GreedyRosterGenerator()
                .generate(
                    new GenerationRequest(
                        problem.draft(),
                        problem.shifts(),
                        shuffled,
                        problem.constraints(),
                        problem.heldElsewhere()));
        assertThat(breachesOf(problem, legal)).describedAs("seed %d", seed).isEmpty();

        RosterModel model = RosterModel.of(problem);
        assertThat(model.choices().keySet())
            .describedAs("seed %d: a legal assignment the model never offers", seed)
            .containsAll(legal);
        model
            .choices()
            .forEach(
                (pair, chosen) -> model.model().addEquality(chosen, legal.contains(pair) ? 1 : 0));
        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(LIMIT.toSeconds());

        assertThat(solver.solve(model.model()))
            .describedAs("seed %d: a legal roster the model refuses", seed)
            .isIn(CpSolverStatus.OPTIMAL, CpSolverStatus.FEASIBLE);
      }
    }
  }

  /**
   * ADR-045: a solve must hold no connection, so asking for one inside a transaction is a mistake
   * in the caller, refused before anything else.
   */
  @Test
  void GivenATransactionAlreadyOpen_WhenGenerating_ThenItIsRefused() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(
              () ->
                  generator.generate(
                      request(List.of(), List.of(anyone("Ada")), ConstraintSet.initial(ACME))))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void GivenAShiftNobodyCanCover_WhenGenerating_ThenTheGapIsMeasuredRatherThanOnlyLeft() {
    generator.generate(
        request(
            List.of(shift("2026-10-05T09:00:00Z", "2026-10-05T17:00:00Z", 3, Set.of())),
            List.of(),
            ConstraintSet.initial(ACME)));

    assertThat(summary("hrsched.shifts.required").totalAmount()).isEqualTo(3.0);
    assertThat(summary("hrsched.shifts.uncovered").totalAmount()).isEqualTo(3.0);
  }

  @Test
  void GivenAFullyCoveredShift_WhenGenerating_ThenNoGapIsRecorded() {
    generator.generate(
        request(
            List.of(shift("2026-10-05T09:00:00Z", "2026-10-05T17:00:00Z", 1, Set.of())),
            List.of(anyone("Ada")),
            ConstraintSet.initial(ACME)));

    assertThat(summary("hrsched.shifts.required").totalAmount()).isEqualTo(1.0);
    assertThat(summary("hrsched.shifts.uncovered").totalAmount()).isZero();
  }

  @Test
  void GivenAPartlyCoveredShift_WhenGenerating_ThenOnlyTheUnfilledPlacesCount() {
    generator.generate(
        request(
            List.of(shift("2026-10-05T09:00:00Z", "2026-10-05T17:00:00Z", 3, Set.of())),
            List.of(anyone("Ada")),
            ConstraintSet.initial(ACME)));

    assertThat(summary("hrsched.shifts.required").totalAmount()).isEqualTo(3.0);
    assertThat(summary("hrsched.shifts.uncovered").totalAmount()).isEqualTo(2.0);
  }

  /** People carried over fill their places, so those places are not gaps. */
  @Test
  void GivenAShiftFilledByPeopleCarriedOver_WhenGenerating_ThenNoGapIsRecorded() {
    Shift monday = shift("2026-10-05T09:00:00Z", "2026-10-05T17:00:00Z", 1, Set.of());
    RosterCandidate grace = anyone("Grace");

    generator.generate(carrying(monday, grace, List.of(grace), ConstraintSet.initial(ACME)));

    assertThat(summary("hrsched.shifts.required").totalAmount()).isEqualTo(1.0);
    assertThat(summary("hrsched.shifts.uncovered").totalAmount()).isZero();
  }

  /**
   * A generation asked for nothing is a different event from no generation at all, and a metric
   * that only appears on failure cannot show a recovery.
   */
  @Test
  void GivenNoShiftsAtAll_WhenGenerating_ThenTheGenerationIsStillCounted() {
    generator.generate(request(List.of(), List.of(anyone("Ada")), ConstraintSet.initial(ACME)));

    assertThat(summary("hrsched.shifts.required").count()).isEqualTo(1L);
    assertThat(summary("hrsched.shifts.uncovered").count()).isEqualTo(1L);
  }

  @Test
  void GivenNoTimeLimit_WhenConfigured_ThenItIsRefused() {
    assertThatThrownBy(() -> new GenerationProperties(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GenerationProperties(null))
        .isInstanceOf(NullPointerException.class);
  }
}
