package io.github.naimelijah.hrsched.scheduling.adapter.out.generation;

import io.github.naimelijah.hrsched.scheduling.application.port.out.BackgroundWork;
import io.github.naimelijah.hrsched.scheduling.domain.model.TooManyGenerationsException;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Runs each generation on a thread of its own, at most {@code hrsched.generation.concurrent-runs}
 * at once (ADR-046).
 *
 * <p>Nothing queues. A generation asked for while every thread is busy is refused, so one begins
 * the moment it is accepted, and its lost-at instant measures a solve rather than a wait. Each
 * solve already uses every core, so running more at once would only make each one slower.
 *
 * <p>The logging context is carried to the thread, so the solve's log lines carry the trace id of
 * the request that asked for it (ADR-016). What the work throws is logged here, at ERROR, because
 * the request that could have been told has already been answered.
 *
 * <p>The threads are daemons. A solve is native code that an interrupt does not stop, and an
 * instance shutting down must not wait minutes for one: the generation it was running reads as lost
 * instead.
 */
@Component
public class GenerationRunner implements BackgroundWork {

  private static final Logger log = LoggerFactory.getLogger(GenerationRunner.class);

  private final int concurrentRuns;
  private final ThreadPoolExecutor runs;

  /**
   * @param properties how many generations may run at once
   */
  public GenerationRunner(GenerationProperties properties) {
    this.concurrentRuns = properties.concurrentRuns();
    AtomicInteger started = new AtomicInteger();
    this.runs =
        new ThreadPoolExecutor(
            concurrentRuns,
            concurrentRuns,
            0L,
            TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            work -> {
              Thread thread = new Thread(work, "generation-" + started.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
  }

  @Override
  public void start(Runnable work) {
    Map<String, String> context = MDC.getCopyOfContextMap();
    try {
      runs.execute(() -> run(work, context));
    } catch (RejectedExecutionException everyThreadBusy) {
      throw new TooManyGenerationsException(concurrentRuns);
    }
  }

  private static void run(Runnable work, Map<String, String> context) {
    if (context != null) {
      MDC.setContextMap(context);
    }
    try {
      work.run();
    } catch (RuntimeException failure) {
      log.atError().setCause(failure).log("Roster generation failed");
    } finally {
      MDC.clear();
    }
  }

  /** Stops taking generations. Any still solving are left to end, or to read as lost. */
  @PreDestroy
  void stop() {
    runs.shutdown();
  }
}
