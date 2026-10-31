package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import java.net.URI;

/**
 * The type URI of each kind of problem the workforce context answers with (ADR-057).
 *
 * <p>A kind keeps one URI wherever it is answered, so a client can tell refusals apart without
 * reading their titles. A kind answered in more than one place is written down in each place's own
 * {@code ProblemTypes}, because a context may not reach another's adapter (ADR-008); {@code
 * ProblemTypesTest} holds every constant to its name in kebab case under {@code
 * urn:hrsched:problem:}, which keeps the copies equal. A URI names a kind and is never fetched:
 * nothing is served at it.
 */
final class ProblemTypes {

  static final URI INVALID_REQUEST = URI.create("urn:hrsched:problem:invalid-request");

  static final URI MALFORMED_REQUEST = URI.create("urn:hrsched:problem:malformed-request");

  static final URI NOT_FOUND = URI.create("urn:hrsched:problem:not-found");

  static final URI NOT_PERMITTED = URI.create("urn:hrsched:problem:not-permitted");

  static final URI OVERLAPS_A_DECLARED_SPAN =
      URI.create("urn:hrsched:problem:overlaps-a-declared-span");

  static final URI REQUEST_TOO_LARGE = URI.create("urn:hrsched:problem:request-too-large");

  private ProblemTypes() {}
}
