package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Where a swap request has got to.
 *
 * <p>{@code REQUESTED} to {@code APPROVED} or {@code REJECTED}, and no other transition is legal. A
 * decision is final: there is no route back to {@code REQUESTED}, because approving one has already
 * published a roster people are arranging their lives around, and undoing that means asking for
 * another swap rather than changing your mind about this one.
 */
public enum SwapRequestStatus {

  /** Asked for, and waiting on a Scheduler. */
  REQUESTED,

  /** A Scheduler agreed, and a replacement roster was published. */
  APPROVED,

  /** A Scheduler declined. Nothing about the roster changed. */
  REJECTED
}
