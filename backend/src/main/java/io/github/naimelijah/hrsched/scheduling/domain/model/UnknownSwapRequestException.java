package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when an operation names a swap request the organisation does not have.
 *
 * <p>Reported as absence rather than as a permission failure, so that another organisation's swap
 * request and one that never existed are the same answer (ADR-009).
 */
public class UnknownSwapRequestException extends RuntimeException {

  /**
   * @param request the identity that was asked for; the message carries it, and the 404 response
   *     replaces it with a constant, so it reaches no caller
   */
  public UnknownSwapRequestException(SwapRequestId request) {
    super("No such swap request %s".formatted(request.value()));
  }
}
