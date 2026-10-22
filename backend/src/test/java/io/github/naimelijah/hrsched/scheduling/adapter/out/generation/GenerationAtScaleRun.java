package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import static io.github.naimelijah.hrsched.scheduling.adapter.out.generation.RosterGeneratorContract.breachesOf;
import static io.github.naimelijah.hrsched.scheduling.adapter.out.generation.RosterGeneratorContract.scoreOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.scheduling.application.port.out.GenerationRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationPolicy;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.springframework.boot.convert.DurationStyle;

/**
 * Generation at the largest size the requirements state: 1,000 employees and 500 shifts in one
 * period, under the time limit the application is configured with (#28, #143).
 *
 * <p>A measurement, not a gate: it takes as long as the configured limit allows, and what it
 * reports depends on the machine. So it is named without a Test suffix, Surefire leaves it out of
 * the build, and it runs only when asked for:
 *
 * <pre>./mvnw spotless:apply test -Dtest=GenerationAtScaleRun</pre>
 *
 * <p>The generator logs each search's status and the places left empty; the run asserts only that
 * the answer is legal, and reports the score against greedy's through JUnit. The problems are
 * {@link GenerationInstances}', so every value in them is invented.
 *
 * <p>Run it where memory is free. On the 8-core, 16 GB machine it was written on, a run of both
 * searches at this size took the test JVM to about 7 GB, mostly the solver's native memory, and was
 * stopped for want of more before it finished. The coverage search alone, run on its own, spent 75
 * s in presolve and reached 978 of 980 places after 139 s.
 */
class GenerationAtScaleRun {

  private static final int PEOPLE = 1_000;
  private static final int SHIFTS = 500;
  private static final long SEED = 1;

  @Test
  void GivenTheLargestStatedSize_WhenGenerating_ThenALegalRosterIsFoundWithinTheConfiguredLimit(
      TestReporter report) throws IOException {
    GenerationRequest problem = GenerationInstances.instance(SEED, PEOPLE, SHIFTS);
    CpSatRosterGenerator solver =
        new CpSatRosterGenerator(
            new SimpleMeterRegistry(),
            new GenerationProperties(configured("hrsched.generation.time-limit-per-phase"), 1),
            new GenerationPolicy(configured("hrsched.generation.lost-after")));

    long started = System.nanoTime();
    List<Assignment> solved = solver.generate(problem);
    Duration took = Duration.ofNanos(System.nanoTime() - started);
    List<Assignment> greedily = new GreedyRosterGenerator().generate(problem);

    int places = problem.shifts().stream().mapToInt(Shift::requiredHeadcount).sum();
    report.publishEntry("places asked for", String.valueOf(places));
    report.publishEntry(
        "places filled, solver against greedy",
        "%d against %d".formatted(solved.size(), greedily.size()));
    report.publishEntry(
        "weighted score, solver against greedy",
        "%d against %d".formatted(scoreOf(problem, solved), scoreOf(problem, greedily)));
    report.publishEntry("generation took", took.toString());
    assertThat(breachesOf(problem, solved)).isEmpty();
  }

  /** A duration production runs under, read from where the application reads it. */
  private static Duration configured(String key) throws IOException {
    Properties application = new Properties();
    try (InputStream properties =
        GenerationAtScaleRun.class.getResourceAsStream("/application.properties")) {
      application.load(properties);
    }
    return DurationStyle.detectAndParse(application.getProperty(key));
  }
}
