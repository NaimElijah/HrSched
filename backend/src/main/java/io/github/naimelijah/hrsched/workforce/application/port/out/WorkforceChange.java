package io.github.naimelijah.hrsched.workforce.application.port.out;

/**
 * The workforce changes worth recording in the audit trail.
 *
 * <p>An enum rather than a string, so a change that is recorded is a change somebody declared. It
 * lists state transitions only: reads are not audited, or the writes would be buried under them.
 * Recording is not storing: the trail is a log stream today (ADR-018).
 *
 * <p>Several of these are changes an employee may make to their own record, which is exactly why
 * they are recorded — the trail has to answer who changed it as well as what changed.
 */
public enum WorkforceChange {

  /** Somebody joined the organisation's records. */
  EMPLOYEE_ADDED,

  /** A qualification now exists to be granted. */
  QUALIFICATION_DEFINED,

  /** Somebody became eligible for work they could not previously cover. */
  QUALIFICATION_GRANTED,

  /** Contracted hours were set, replacing any there were. */
  CONTRACT_SET,

  /** Somebody declared time they cannot work. */
  UNAVAILABILITY_DECLARED,

  /** Somebody stated a soft preference, which binds nothing and is honoured where it can be. */
  PREFERENCE_STATED,

  /**
   * Somebody withdrew time they had declared they cannot work, so they may be rostered across it.
   * The declaration itself is not kept (ADR-040); this record of the act is.
   */
  UNAVAILABILITY_WITHDRAWN,

  /** Somebody withdrew a soft preference they had stated. */
  PREFERENCE_WITHDRAWN
}
