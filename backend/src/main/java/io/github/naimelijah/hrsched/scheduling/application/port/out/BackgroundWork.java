package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.TooManyGenerationsException;

/**
 * Driven port: running a generation after the request that asked for it has been answered
 * (ADR-046).
 */
@FunctionalInterface
public interface BackgroundWork {

  /**
   * Runs {@code work} on a thread of its own, now, rather than queued behind others.
   *
   * <p>What {@code work} throws is its own to record: it is not handed back to the caller, who has
   * gone by then.
   *
   * @throws TooManyGenerationsException if as many generations as may run at once already are
   */
  void start(Runnable work);
}
