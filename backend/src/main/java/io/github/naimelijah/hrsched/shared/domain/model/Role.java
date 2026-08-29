package io.github.naimelijah.hrsched.shared.domain.model;

/**
 * What a user may do within their organisation.
 *
 * <p>Deliberately does not include the platform operator: an {@code OPERATOR} value here would
 * imply an operator belongs to an organisation, which is exactly what it does not. See {@link
 * Actor}.
 */
public enum Role {

  /** Sees their own roster, declares their own unavailability and preferences. */
  EMPLOYEE,

  /** Builds, generates and publishes rosters; sees every employee in the organisation. */
  SCHEDULER,

  /** Manages employees, qualifications and the constraint set. Does not build rosters. */
  ADMIN
}
