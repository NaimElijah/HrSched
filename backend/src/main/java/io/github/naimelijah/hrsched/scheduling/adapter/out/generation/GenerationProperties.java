package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long the solver may search, and how many solves an instance runs at once (ADR-044, ADR-046).
 *
 * <p>Configuration rather than constants, because how long a Scheduler may wait for a draft, and
 * how many solves the hardware can bear at once, depend on where this runs, which code cannot know.
 * Neither has a default in this record, so configuration that leaves one out fails to start rather
 * than running on whatever somebody once guessed; {@code application.properties} sets both.
 *
 * @param timeLimitPerPhase the longest each of the solver's searches may run before it answers with
 *     the best roster it has found
 * @param concurrentRuns the most generations an instance solves at once; one asked for beyond that
 *     is refused rather than queued
 * @throws IllegalArgumentException if the limit is not positive, or no run is allowed at all
 */
@ConfigurationProperties("hrsched.generation")
public record GenerationProperties(Duration timeLimitPerPhase, int concurrentRuns) {

  public GenerationProperties {
    Objects.requireNonNull(
        timeLimitPerPhase, "hrsched.generation.time-limit-per-phase must be configured");
    if (timeLimitPerPhase.isNegative() || timeLimitPerPhase.isZero()) {
      throw new IllegalArgumentException(
          "hrsched.generation.time-limit-per-phase must be positive, but is " + timeLimitPerPhase);
    }
    if (concurrentRuns < 1) {
      throw new IllegalArgumentException(
          "hrsched.generation.concurrent-runs must be at least one, but is " + concurrentRuns);
    }
  }
}
