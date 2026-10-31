package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when somebody asks to hand a shift to themself.
 *
 * <p>An invariant rather than a validation, because a swap that changes nothing would still travel
 * the whole approval path and publish a replacement roster identical to the one it superseded — so
 * the harm is not the nonsense request, it is the real roster churn behind it.
 *
 * <p>The message becomes a 400's detail, so it is a constant: the caller sent the identifier, and
 * is not told it back.
 */
public class SwapWithThemselfException extends RuntimeException {

  /** Builds the refusal with a constant message, which is the 400 response's detail. */
  public SwapWithThemselfException() {
    super("Nobody can swap a shift with themself.");
  }
}
