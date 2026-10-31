package io.github.naimelijah.hrsched.scheduling.domain.model;

/** Raised when no generation with the given identity exists in the organisation being acted on. */
public class UnknownGenerationException extends RuntimeException {

  /**
   * @param id the identity that was asked for; the message carries it, and the 404 response
   *     replaces it with a constant, so it reaches no caller
   */
  public UnknownGenerationException(GenerationId id) {
    super("No such generation: " + id.value());
  }
}
