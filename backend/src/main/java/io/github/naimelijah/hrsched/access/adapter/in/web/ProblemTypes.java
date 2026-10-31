package io.github.naimelijah.hrsched.access.adapter.in.web;

import java.net.URI;

/**
 * The type URI of each kind of problem the access context answers with (ADR-057).
 *
 * <p>A kind keeps one URI wherever it is answered, so a client can tell refusals apart without
 * reading their titles. A kind answered in more than one place is written down in each place's own
 * {@code ProblemTypes}, because a context may not reach another's adapter (ADR-008); {@code
 * ProblemTypesTest} holds every constant to its name in kebab case under {@code
 * urn:hrsched:problem:}, which keeps the copies equal. A URI names a kind and is never fetched:
 * nothing is served at it.
 */
final class ProblemTypes {

  static final URI EMPLOYEE_ACCOUNT_NOT_LINKED =
      URI.create("urn:hrsched:problem:employee-account-not-linked");

  static final URI EMPLOYEE_ALREADY_LINKED =
      URI.create("urn:hrsched:problem:employee-already-linked");

  static final URI INVALID_REQUEST = URI.create("urn:hrsched:problem:invalid-request");

  static final URI LAST_ACTIVE_ADMIN = URI.create("urn:hrsched:problem:last-active-admin");

  static final URI MALFORMED_REQUEST = URI.create("urn:hrsched:problem:malformed-request");

  static final URI NOT_FOUND = URI.create("urn:hrsched:problem:not-found");

  static final URI NOT_PERMITTED = URI.create("urn:hrsched:problem:not-permitted");

  static final URI NOT_YOUR_OWN_PASSWORD = URI.create("urn:hrsched:problem:not-your-own-password");

  static final URI PASSWORD_NOT_ACCEPTED = URI.create("urn:hrsched:problem:password-not-accepted");

  static final URI PASSWORD_NOT_CHANGED = URI.create("urn:hrsched:problem:password-not-changed");

  static final URI REQUEST_TOO_LARGE = URI.create("urn:hrsched:problem:request-too-large");

  static final URI SIGN_IN_NAME_TAKEN = URI.create("urn:hrsched:problem:sign-in-name-taken");

  static final URI SIGN_IN_REFUSED = URI.create("urn:hrsched:problem:sign-in-refused");

  static final URI UNKNOWN_EMPLOYEE = URI.create("urn:hrsched:problem:unknown-employee");

  private ProblemTypes() {}
}
