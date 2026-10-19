package io.github.naimelijah.hrsched.shared.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two writers racing on real connections to the suite's PostgreSQL, for an adapter test that has to
 * show what one transaction does to another rather than assert it.
 *
 * <p>Each writer runs in its own transaction on its own thread, and is told where to wait for the
 * other, so a test decides what both have done before either goes on. Nothing sleeps: a wait for a
 * lock is read from PostgreSQL as soon as it begins.
 */
public final class RacingTransactions {

  /**
   * How long a racing writer may take before the test gives up on it. Far longer than any write
   * here needs, including PostgreSQL's one-second wait before it resolves a deadlock, so reaching
   * it means something is stuck rather than slow.
   */
  public static final long STUCK_AFTER_SECONDS = 30;

  private RacingTransactions() {}

  /** One writer's work, told when to wait for the other writer to reach the same point. */
  @FunctionalInterface
  public interface Writer {

    /**
     * Does this writer's work inside its own transaction.
     *
     * @param waitForTheOther returns once the other writer has called its own, or throws if it
     *     never does
     */
    void write(Runnable waitForTheOther);
  }

  /**
   * Runs two writers at once, each in its own transaction on its own connection, and returns what
   * refused them.
   *
   * <p>The executor is waited for as well as stopped: a writer still running would keep its
   * connection, and the next test's truncation would wait on it.
   *
   * @return what each refused writer threw, in the order the writers were given; empty when both
   *     committed
   * @throws IllegalStateException if a writer is still running once both have been given up on
   */
  public static List<Throwable> refusalsOf(Writer first, Writer second)
      throws InterruptedException, TimeoutException {
    TransactionTemplate transaction = new TransactionTemplate(PostgresDatabase.transactions());
    CyclicBarrier together = new CyclicBarrier(2);
    Runnable waitForTheOther =
        () -> {
          try {
            together.await(STUCK_AFTER_SECONDS, TimeUnit.SECONDS);
          } catch (InterruptedException | BrokenBarrierException | TimeoutException stuck) {
            throw new IllegalStateException("The other writer never got there", stuck);
          }
        };
    ExecutorService writers = Executors.newFixedThreadPool(2);
    List<Throwable> refusals = new ArrayList<>();
    try {
      List<Future<?>> running =
          List.of(
              writers.submit(
                  () -> transaction.executeWithoutResult(status -> first.write(waitForTheOther))),
              writers.submit(
                  () -> transaction.executeWithoutResult(status -> second.write(waitForTheOther))));
      for (Future<?> writer : running) {
        try {
          writer.get(STUCK_AFTER_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException refused) {
          refusals.add(refused.getCause());
        }
      }
    } finally {
      writers.shutdownNow();
      if (!writers.awaitTermination(STUCK_AFTER_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException("A writer is still running and holding its connection");
      }
    }
    return refusals;
  }

  /**
   * @return the PostgreSQL backend serving the calling thread's transaction, which joins it
   */
  public static int backendServingThisTransaction() {
    return PostgresDatabase.jdbcClient()
        .sql("SELECT pg_backend_pid()")
        .query(Integer.class)
        .single();
  }

  /**
   * Returns once the backend {@code pid} is waiting for a lock some other transaction holds.
   *
   * <p>Asks PostgreSQL rather than sleeping, so it returns as soon as the wait begins and never
   * guesses how long that takes; the bound is only there to fail a test that would otherwise hang.
   *
   * @throws IllegalStateException if the backend has not waited within {@link #STUCK_AFTER_SECONDS}
   */
  public static void awaitWaitingOnALock(int pid) {
    awaitWaitingOnALockOr(pid, () -> false);
  }

  /**
   * As {@link #awaitWaitingOnALock}, but also returns once {@code wentOnWithoutWaiting} says the
   * other writer got past the point where it should have waited.
   *
   * <p>For a test whose failure is that very thing: a read that should wait for a lock and does
   * not. Without the second way out, the test would hang until its timeout rather than fail on what
   * it asserts.
   *
   * @throws IllegalStateException if neither has happened within {@link #STUCK_AFTER_SECONDS}
   */
  public static void awaitWaitingOnALockOr(int pid, BooleanSupplier wentOnWithoutWaiting) {
    long giveUpAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(STUCK_AFTER_SECONDS);
    while (!wentOnWithoutWaiting.getAsBoolean() && !isWaitingOnALock(pid)) {
      if (System.nanoTime() > giveUpAt) {
        throw new IllegalStateException("The other writer never waited for a lock");
      }
    }
  }

  /**
   * Returns once some backend is waiting on a lock the backend {@code holder} holds, or once {@code
   * other} has finished without waiting - which is the failure a caller is usually testing for, and
   * should fail on its own assertion rather than hang.
   *
   * <p>For a race whose waiting side is not known in advance, such as a request served by whatever
   * connection its pool lends. Asks the live {@code pg_locks} view, from a connection of its own
   * and outside any transaction: {@code pg_stat_activity} is snapshotted once per transaction, so a
   * backend that connected after the first look would never be seen.
   *
   * @throws IllegalStateException if neither has happened within {@link #STUCK_AFTER_SECONDS}
   */
  public static void awaitSomebodyWaitingOn(int holder, Future<?> other) {
    long giveUpAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(STUCK_AFTER_SECONDS);
    while (!other.isDone() && !isAnybodyWaitingOn(holder)) {
      if (System.nanoTime() > giveUpAt) {
        throw new IllegalStateException("Nothing ever waited on the holder");
      }
    }
  }

  private static boolean isAnybodyWaitingOn(int holder) {
    return PostgresDatabase.jdbcClient()
        .sql(
            "SELECT EXISTS (SELECT 1 FROM pg_locks"
                + " WHERE NOT granted AND :holder = ANY (pg_blocking_pids(pid)))")
        .param("holder", holder)
        .query(Boolean.class)
        .single();
  }

  private static boolean isWaitingOnALock(int pid) {
    return PostgresDatabase.jdbcClient()
        .sql("SELECT cardinality(pg_blocking_pids(:pid)) > 0")
        .param("pid", pid)
        .query(Boolean.class)
        .single();
  }
}
