package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.scheduling.domain.model.TooManyGenerationsException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Every run busy is answered 503 (ADR-046): nothing about the request was wrong, and asking again
 * shortly may succeed. Specified on the handler itself, because the suite's shared context runs
 * generations on the calling thread and so is never busy.
 */
class GenerationRefusalsTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final SchedulingExceptionHandler handler = new SchedulingExceptionHandler(meters);

  @Test
  void GivenEveryRunBusy_WhenRefused_ThenItIsAServiceUnavailableAndItIsCounted() {
    ProblemDetail problem = handler.handleTooManyGenerations(new TooManyGenerationsException(2));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
    assertThat(problem.getTitle()).isEqualTo("Generation is busy");
    assertThat(meters.get("hrsched.generations.busy").counter().count()).isEqualTo(1.0);
  }
}
