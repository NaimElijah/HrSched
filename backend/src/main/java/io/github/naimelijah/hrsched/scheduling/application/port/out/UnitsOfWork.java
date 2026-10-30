package io.github.naimelijah.hrsched.scheduling.application.port.out;

import java.util.function.Supplier;

/**
 * Driven port: a unit of work a use case marks out for itself.
 *
 * <p>Every other use case is one transaction without saying so, wrapped at the composition root
 * (ADR-023). Generation is the one exception (ADR-045): a solve may run for up to two searches of
 * {@code hrsched.generation.time-limit-per-phase} each (ADR-046), and inside a transaction it would
 * hold a pooled connection all that time. So it reads in one unit, solves in none, and writes in
 * another, and these are the units.
 *
 * <p>A unit opened inside another joins it rather than nesting.
 */
public interface UnitsOfWork {

  /**
   * Runs {@code work} as one unit that only reads.
   *
   * @return what {@code work} answers
   */
  <T> T reading(Supplier<T> work);

  /**
   * Runs {@code work} as one unit: everything it writes is stored, or, if it throws, nothing is.
   *
   * @return what {@code work} answers
   */
  <T> T writing(Supplier<T> work);
}
