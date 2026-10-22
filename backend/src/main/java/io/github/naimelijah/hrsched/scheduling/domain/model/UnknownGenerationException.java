package io.github.naimelijah.hrsched.scheduling.domain.model;

/** Raised when no generation with the given identity exists in the organisation being acted on. */
public class UnknownGenerationException extends RuntimeException {

  public UnknownGenerationException(GenerationId id) {
    super("No such generation: " + id.value());
  }
}
