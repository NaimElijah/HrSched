package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long the solver may search (ADR-044).
 *
 * <p>Configuration rather than a constant, because how long a Scheduler may wait for a draft
 * depends on what sits in front of this service and on the hardware it runs on, neither of which
 * code can know. It has no default here, so a deployment that forgot it fails to start rather than
 * solving for however long somebody once guessed.
 *
 * @param timeLimitPerPhase the longest each of the solver's searches may run before it answers with
 *     the best roster it has found
 * @throws IllegalArgumentException if the limit is not positive
 */
@ConfigurationProperties("hrsched.generation")
public record GenerationProperties(Duration timeLimitPerPhase) {

  public GenerationProperties {
    Objects.requireNonNull(
        timeLimitPerPhase, "hrsched.generation.time-limit-per-phase must be configured");
    if (timeLimitPerPhase.isNegative() || timeLimitPerPhase.isZero()) {
      throw new IllegalArgumentException(
          "hrsched.generation.time-limit-per-phase must be positive, but is " + timeLimitPerPhase);
    }
  }
}
