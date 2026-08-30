package io.github.naimelijah.hrsched.workforce.application.port.out;

/**
 * The workforce changes worth a permanent record.
 *
 * <p>An enum rather than a string, so a change that is recorded is a change somebody declared. It
 * lists state transitions only: reads are not audited, or the writes would be buried under them.
 *
 * <p>Several of these are changes an employee may make to their own record, which is exactly why
 * they are recorded - the trail has to answer who changed it as well as what changed.
 */
public enum WorkforceChange {

  /** Somebody joined the organisation's records. */
  EMPLOYEE_ADDED,

  /** A qualification now exists to be granted. */
  QUALIFICATION_DEFINED,

  /** Somebody became eligible for work they could not previously cover. */
  QUALIFICATION_GRANTED,

  /** Contracted hours changed, which changes what the working-time rules judge them against. */
  CONTRACT_SET,

  /** Somebody declared time they cannot work. */
  UNAVAILABILITY_DECLARED,

  /** Somebody stated a soft preference, which binds nothing and is honoured where it can be. */
  PREFERENCE_STATED
}
