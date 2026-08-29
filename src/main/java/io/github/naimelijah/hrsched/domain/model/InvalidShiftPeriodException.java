package io.github.naimelijah.hrsched.domain.model;

import java.time.Instant;

/**
 * Raised when a shift period would end at or before it starts.
 *
 * <p>A domain exception, deliberately unchecked and free of any framework type: adapters translate
 * it into whatever their transport considers a client error.
 */
public class InvalidShiftPeriodException extends RuntimeException {

  /**
   * @param start the rejected start instant
   * @param end the rejected end instant, which was not strictly after {@code start}
   */
  public InvalidShiftPeriodException(Instant start, Instant end) {
    super("A shift must end after it starts, but got start=%s end=%s".formatted(start, end));
  }
}
