package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when as many generations as an instance may run at once are already running (ADR-046).
 *
 * <p>Nothing about the request was wrong, and asking again shortly may succeed.
 */
public class TooManyGenerationsException extends RuntimeException {

  /**
   * @param running how many are running, which is the most allowed
   */
  public TooManyGenerationsException(int running) {
    super("%d generations are already running; ask again shortly".formatted(running));
  }
}
