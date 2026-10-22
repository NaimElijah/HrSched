package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import static io.github.naimelijah.hrsched.scheduling.adapter.out.generation.RosterGeneratorContract.scoreOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.scheduling.application.port.out.GenerationRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * #28's yardstick: on problems nobody wrote by hand, the solver beats the greedy generator it
 * replaced, first on places filled (F11) and then on ADR-010's weighted score, judged by the
 * domain's own measures rather than by either generator.
 *
 * <p>Small enough that the result does not hang on how fast the machine is: the solver proves
 * nearly every answer optimal in well under a second, and on the one it cannot within the limit
 * (seed 9, a 25-hour week under a 12-hour day) it is seven places ahead of greedy within the first
 * second and one short of the proven bound. How it does at the stated scale, where the time limit
 * binds, is measured apart from the build.
 *
 * <p>Every value in the problems is invented; see {@link GenerationInstances}.
 */
class GenerationBenchmarkTest {

  /** Invented: ample for these problems to be solved to proven optimality. */
  private static final Duration LIMIT = Duration.ofSeconds(10);

  private static final int SEEDS = 20;
  private static final int PEOPLE = 15;
  private static final int SHIFTS = 25;

  @Test
  void GivenSeededProblems_WhenBothGenerate_ThenTheSolverIsNeverWorseAndBetterOverall(
      TestReporter report) {
    CpSatRosterGenerator solver =
        new CpSatRosterGenerator(
            new SimpleMeterRegistry(),
            new GenerationProperties(LIMIT, 1),
            // Invented: longer than both searches together, as the generator requires.
            new GenerationPolicy(LIMIT.multipliedBy(3)));
    GreedyRosterGenerator greedy = new GreedyRosterGenerator();
    int solverPlaces = 0;
    int greedyPlaces = 0;
    long solverScore = 0;
    long greedyScore = 0;

    for (long seed = 1; seed <= SEEDS; seed++) {
      GenerationRequest problem = GenerationInstances.instance(seed, PEOPLE, SHIFTS);
      List<Assignment> solved = solver.generate(problem);
      List<Assignment> greedily = greedy.generate(problem);

      long solvedScore = scoreOf(problem, solved);
      long greedilyScore = scoreOf(problem, greedily);
      assertThat(solved.size())
          .describedAs("seed %d: places", seed)
          .isGreaterThanOrEqualTo(greedily.size());
      if (solved.size() == greedily.size()) {
        assertThat(solvedScore)
            .describedAs("seed %d: score at equal places", seed)
            .isLessThanOrEqualTo(greedilyScore);
      }
      solverPlaces += solved.size();
      greedyPlaces += greedily.size();
      solverScore += solvedScore;
      greedyScore += greedilyScore;
    }

    report.publishEntry(
        "places filled, solver against greedy",
        "%d against %d".formatted(solverPlaces, greedyPlaces));
    report.publishEntry(
        "weighted score, solver against greedy",
        "%d against %d".formatted(solverScore, greedyScore));
    assertThat(solverPlaces > greedyPlaces || solverScore < greedyScore)
        .describedAs(
            "better overall: %d places against %d, score %d against %d",
            solverPlaces, greedyPlaces, solverScore, greedyScore)
        .isTrue();
  }
}
