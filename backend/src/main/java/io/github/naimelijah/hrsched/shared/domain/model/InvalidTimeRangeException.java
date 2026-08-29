package io.github.naimelijah.hrsched.shared.domain.model;

import java.time.Instant;

/**
 * Raised when a time range would end at or before it starts.
 *
 * <p>A domain exception, deliberately unchecked and free of any framework type: adapters translate
 * it into whatever their transport considers a client error.
 */
public class InvalidTimeRangeException extends RuntimeException {

  public InvalidTimeRangeException(Instant start, Instant end) {
    super("A time range must end after it starts, but got start=%s end=%s".formatted(start, end));
  }
}
