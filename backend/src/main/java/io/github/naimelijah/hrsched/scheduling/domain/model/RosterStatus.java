package io.github.naimelijah.hrsched.scheduling.domain.model;

/**
 * Where a roster is in its life.
 *
 * <p>A published roster is never edited; it is superseded by a newer one. That single mechanism
 * covers both a rule change and emergency cover, which is why neither needs an override.
 */
public enum RosterStatus {

  /** Being built. Assignments may be added and removed. */
  DRAFT,

  /** Visible to employees, and fixed. Validated against a recorded constraint version. */
  PUBLISHED,

  /** Replaced by a newer roster, and kept as a record of what was once planned. */
  SUPERSEDED
}
