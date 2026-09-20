package io.github.naimelijah.hrsched.access.application.port.out;

/**
 * The access changes worth a permanent record.
 *
 * <p>An enum rather than a string for the same reason its siblings are: a change that is recorded
 * is a change somebody declared, and the vocabulary is closed.
 */
public enum AccessChange {

  /** A new organisation joined the platform, creating a tenant boundary that did not exist. */
  ORGANISATION_ONBOARDED,

  /**
   * The platform operator was created from configuration at startup. Recorded because it is the
   * most privileged account there is, and the one nobody signs in to create.
   */
  OPERATOR_BOOTSTRAPPED
}
