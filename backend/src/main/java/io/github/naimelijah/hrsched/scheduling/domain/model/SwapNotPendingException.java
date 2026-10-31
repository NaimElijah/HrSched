package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Raised when a swap request is no longer waiting on anybody: decided twice, or decided after it
 * lapsed with its roster (R33).
 *
 * <p>Two Schedulers with the request open in front of them is the ordinary way the first happens,
 * and the second one must be told rather than allowed to publish a second replacement roster over
 * the first. The store raises it too, for a writer that read the request pending and finds it ended
 * by the time it writes.
 */
public class SwapNotPendingException extends RuntimeException {

  /**
   * Builds the refusal. The message names the request and the status it has reached, and is the 409
   * response's detail.
   *
   * @param request the request that was to be decided
   * @param status where it already stands, which is not {@link SwapRequestStatus#REQUESTED}
   */
  public SwapNotPendingException(SwapRequestId request, SwapRequestStatus status) {
    super("Swap request %s is already %s".formatted(request.value(), status));
  }
}
