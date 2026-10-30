package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationsStoppedException;
import io.github.naimelijah.hrsched.scheduling.domain.model.TooManyGenerationsException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The pool generations run on (ADR-046): another thread, nothing queued, the request's trace
 * carried along, and a failed solve leaving the pool able to run the next.
 *
 * <p>Nothing here sleeps. Each test waits on a latch the work itself counts down, with a bound only
 * so that a defect fails the test rather than hanging the build.
 */
class GenerationRunnerTest {

  /** Invented: the solver's limit plays no part here. */
  private static final Duration ANY_LIMIT = Duration.ofSeconds(1);

  private static final long BOUND_SECONDS = 10;

  private final GenerationRunner runner =
      new GenerationRunner(new GenerationProperties(ANY_LIMIT, 1));

  @AfterEach
  void stop() {
    runner.stop();
    MDC.clear();
  }

  @Test
  void GivenWork_WhenStarted_ThenItRunsOnAThreadOfItsOwn() throws InterruptedException {
    AtomicReference<Thread> ranOn = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);

    runner.start(
        () -> {
          ranOn.set(Thread.currentThread());
          done.countDown();
        });

    assertThat(done.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
    assertThat(ranOn.get()).isNotSameAs(Thread.currentThread());
    assertThat(ranOn.get().getName()).startsWith("generation-");
  }

  /** Nothing queues: one asked for while every thread solves is refused, not kept waiting. */
  @Test
  void GivenEveryThreadBusy_WhenAnotherIsStarted_ThenItIsRefused() throws InterruptedException {
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    runner.start(
        () -> {
          running.countDown();
          awaitQuietly(release);
        });
    assertThat(running.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

    try {
      assertThatThrownBy(() -> runner.start(() -> {}))
          .isInstanceOf(TooManyGenerationsException.class);
    } finally {
      release.countDown();
    }
  }

  /**
   * Once stopped, the pool refuses everything, busy or not. Saying that every run is busy would be
   * false, and would be counted as saturation; the refusal is the port's own for a stopped runner,
   * so the caller can fail the generation as it does a busy one, and keeps what the pool said.
   */
  @Test
  void GivenARunnerThatHasStopped_WhenWorkIsStarted_ThenItIsRefusedAsStoppedNotAsBusy() {
    runner.stop();

    assertThatThrownBy(() -> runner.start(() -> {}))
        .isInstanceOf(GenerationsStoppedException.class)
        .hasCauseInstanceOf(RejectedExecutionException.class);
  }

  /** The solve's log lines carry the trace id of the request that asked for it (ADR-016). */
  @Test
  void GivenATraceInTheLoggingContext_WhenStarted_ThenTheWorkRunsWithinIt()
      throws InterruptedException {
    MDC.put("traceId", "an-invented-trace");
    AtomicReference<String> seen = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);

    runner.start(
        () -> {
          seen.set(MDC.get("traceId"));
          done.countDown();
        });

    assertThat(done.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
    assertThat(seen.get()).isEqualTo("an-invented-trace");
  }

  /** A failed solve is logged where it happened, and the thread is free for the next one. */
  @Test
  void GivenWorkThatFails_WhenTheNextIsStarted_ThenItStillRuns() throws InterruptedException {
    CountDownLatch failed = new CountDownLatch(1);
    runner.start(
        () -> {
          failed.countDown();
          throw new IllegalStateException("an invented defect");
        });
    assertThat(failed.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
    CountDownLatch next = new CountDownLatch(1);

    startWhenFree(next::countDown);

    assertThat(next.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
  }

  /**
   * An Error ending a solve, such as running out of memory, is logged here at ERROR as an exception
   * is, with the trace it ran under, rather than reaching only the thread's default handler. The
   * pool replaces the thread it ends, so the next solve still runs.
   */
  @Test
  void GivenWorkEndedByAnError_WhenTheNextIsStarted_ThenTheErrorWasLoggedAndTheNextStillRuns()
      throws InterruptedException {
    Logger logger =
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(GenerationRunner.class.getName());
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    logger.addAppender(captured);
    try {
      OutOfMemoryError invented = new OutOfMemoryError("an invented error");
      runner.start(
          () -> {
            throw invented;
          });
      CountDownLatch next = new CountDownLatch(1);

      startWhenFree(next::countDown);

      assertThat(next.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
      assertThat(captured.list)
          .singleElement()
          .satisfies(
              logged -> {
                assertThat(logged.getLevel()).isEqualTo(Level.ERROR);
                assertThat(logged.getThrowableProxy().getMessage())
                    .isEqualTo(invented.getMessage());
              });
    } finally {
      logger.detachAppender(captured);
    }
  }

  /**
   * The runner adds nothing of its own to a failure's line but a constant message: it cannot know
   * what the work was about, so anything it attached could only be what the work handed it. Whether
   * the failure itself names anybody is the thrower's to keep, as {@code SchedulingService}'s
   * defect messages do.
   */
  @Test
  void GivenWorkThatFails_WhenItRuns_ThenTheLineIsAConstantCarryingOnlyTheFailure()
      throws InterruptedException {
    Logger logger =
        ((LoggerContext) LoggerFactory.getILoggerFactory())
            .getLogger(GenerationRunner.class.getName());
    ListAppender<ILoggingEvent> captured = new ListAppender<>();
    captured.start();
    logger.addAppender(captured);
    try {
      IllegalStateException invented = new IllegalStateException("an invented defect");
      runner.start(
          () -> {
            throw invented;
          });
      CountDownLatch next = new CountDownLatch(1);

      startWhenFree(next::countDown);

      assertThat(next.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
      assertThat(captured.list)
          .singleElement()
          .satisfies(
              logged -> {
                assertThat(logged.getLevel()).isEqualTo(Level.ERROR);
                assertThat(logged.getFormattedMessage()).isEqualTo("Roster generation failed");
                assertThat(logged.getKeyValuePairs()).isNullOrEmpty();
                assertThat(logged.getThrowableProxy().getMessage())
                    .isEqualTo(invented.getMessage());
              });
    } finally {
      logger.detachAppender(captured);
    }
  }

  /**
   * The failed work's thread returns to the pool just after it logs, so the next start may race it
   * and be refused; it is asked again until accepted, within the bound.
   */
  private void startWhenFree(Runnable work) {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
    while (true) {
      try {
        runner.start(work);
        return;
      } catch (TooManyGenerationsException busy) {
        if (System.nanoTime() > until) {
          throw busy;
        }
        Thread.onSpinWait();
      }
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(BOUND_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
