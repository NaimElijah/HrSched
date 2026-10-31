package io.github.naimelijah.hrsched.scheduling.application.port.out;

/**
 * The scheduling changes worth an audit entry.
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

  /**
   * An organisation's objective weights were replaced (ADR-010). Like the rules, revision replaces
   * them whole, so an objective omitted stops being weighed. No roster's legality changes.
   */
  OBJECTIVES_REVISED,

  /**
   * A draft was asked for, to be generated in the background (ADR-046). The subject is the
   * generation. It ends as ROSTER_GENERATED or as GENERATION_REFUSED, or with nothing more recorded
   * here: failed, because the solve met a defect, logged where it happened, or because every run
   * was busy or the instance was stopping, either of which the request asking for it is refused for
   * at once; or lost, because the instance solving it stopped.
   */
  GENERATION_REQUESTED,

  /** A draft roster was produced. It binds nobody until it is published. */
  ROSTER_GENERATED,

  /**
   * A generation ended without a draft, because work it would carry over from a published roster
   * breaks a rule (#130). Recorded here because it ends in the background, where no request is left
   * to be refused.
   */
  GENERATION_REFUSED,

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
   * A pending swap request ended undecided because the roster it named was superseded (R33).
   * Recorded per request, beside the ROSTER_SUPERSEDED that caused it, because nobody asked for it
   * either.
   */
  SWAP_LAPSED,

  /**
   * A published roster stopped being authoritative because a newer one overlapped it. Recorded
   * separately because nobody asked for it: it is a consequence of publishing something else, and
   * without its own entry a roster would appear to demote itself.
   */
  ROSTER_SUPERSEDED
}
