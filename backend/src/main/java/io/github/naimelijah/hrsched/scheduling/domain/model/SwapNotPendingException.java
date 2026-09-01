package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when a swap request is decided twice.
 *
 * <p>Two Schedulers with the request open in front of them is the ordinary way this happens, and
 * the second one must be told rather than allowed to publish a second replacement roster over the
 * first.
 */
public class SwapNotPendingException extends RuntimeException {

  public SwapNotPendingException(SwapRequestId request, SwapRequestStatus status) {
    super("Swap request %s is already %s".formatted(request.value(), status));
  }
}
