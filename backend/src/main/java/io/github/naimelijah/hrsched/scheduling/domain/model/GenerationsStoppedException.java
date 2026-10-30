package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when the instance asked for a generation has stopped taking them, because it is shutting
 * down (ADR-046).
 *
 * <p>Not saturation: nothing is necessarily running. Nothing about the request was wrong either,
 * and asking again shortly may reach an instance that is not stopping.
 */
public class GenerationsStoppedException extends RuntimeException {

  /**
   * @param cause what refused the work, kept for whoever reads the log rather than the response
   */
  public GenerationsStoppedException(Throwable cause) {
    super(
        "This instance has stopped taking generations as it shuts down; ask again shortly", cause);
  }
}
