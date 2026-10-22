package io.github.naimelijah.hrsched.scheduling.domain.model;

/** Where a {@link RosterGeneration} stands (ADR-046). */
public enum GenerationStatus {
  /** Asked for, and still being solved. */
  PENDING,
  /** Ended with a draft. */
  SUCCEEDED,
  /**
   * Ended without one, because work carried over from a neighbouring roster breaks a rule (#130).
   */
  REFUSED,
  /** Ended without one, because something went wrong that nobody asking could have prevented. */
  FAILED,
  /**
   * Still pending past the instant it should have ended by: the instance solving it stopped. Never
   * stored, only read, so every instance reaches the same answer from the clock.
   */
  LOST
}
