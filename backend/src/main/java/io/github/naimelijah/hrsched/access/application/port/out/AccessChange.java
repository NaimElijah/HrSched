package io.github.naimelijah.hrsched.access.application.port.out;

/**
 * The access changes worth a permanent record.
 *
 * <p>One value today. It is an enum rather than a string for the same reason its siblings are: a
 * change that is recorded is a change somebody declared, and the vocabulary is closed.
 */
public enum AccessChange {

  /** A new organisation joined the platform, creating a tenant boundary that did not exist. */
  ORGANISATION_ONBOARDED
}
