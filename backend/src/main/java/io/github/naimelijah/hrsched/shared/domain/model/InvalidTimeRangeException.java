package io.github.naimelijah.hrsched.shared.domain.model;

/**
 * Raised when a time range would end at or before it starts.
 *
 * <p>A domain exception, deliberately unchecked and free of any framework type: adapters translate
 * it into whatever their transport considers a client error.
 *
 * <p>The message names neither instant on purpose: it becomes a 400's detail, a refusal never
 * repeats what was sent (ADR-024), and a span can say when somebody cannot work.
 */
public class InvalidTimeRangeException extends RuntimeException {

  public InvalidTimeRangeException() {
    super("A time range must end after it starts");
  }
}
