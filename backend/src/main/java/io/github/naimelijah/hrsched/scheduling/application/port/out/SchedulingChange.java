package io.github.naimelijah.hrsched.scheduling.application.port.out;

/**
 * The scheduling changes worth a permanent record.
 *
 * <p>An enum rather than a string, so a change that is recorded is a change somebody declared. It
 * lists state transitions only: reads are not audited, because auditing every read of a roster
 * would bury the writes and this trail exists to answer who changed what.
 */
public enum SchedulingChange {

  /** A shift was defined that somebody now has to cover. */
  SHIFT_DEFINED,

  /**
   * An organisation's working-time rules were replaced. Revision replaces the whole set, so a rule
   * omitted is a rule switched off — which makes this the most consequential change here.
   */
  CONSTRAINTS_REVISED,

  /** A draft roster was produced. It binds nobody until it is published. */
  ROSTER_GENERATED,

  /**
   * Somebody was put on a shift by hand rather than by the generator. Recorded separately from
   * generation because it is a person's judgement rather than the machine's, and the trail should
   * say which.
   */
  ASSIGNMENT_ADDED,

  /** Somebody was taken off a shift by hand. */
  ASSIGNMENT_REMOVED,

  /** A roster became the authoritative answer to who works when. */
  ROSTER_PUBLISHED,

  /** Somebody asked for one of their shifts to be covered by a named colleague. */
  SWAP_REQUESTED,

  /**
   * A Scheduler agreed to a swap. It appears beside the ROSTER_PUBLISHED and ROSTER_SUPERSEDED
   * entries the replacement produced, because those say a roster changed and this says why.
   */
  SWAP_APPROVED,

  /** A Scheduler declined a swap. Nothing about any roster changed. */
  SWAP_REJECTED,

  /**
   * A published roster stopped being authoritative because a newer one overlapped it. Recorded
   * separately because nobody asked for it: it is a consequence of publishing something else, and
   * without its own entry a roster would appear to demote itself.
   */
  ROSTER_SUPERSEDED
}
