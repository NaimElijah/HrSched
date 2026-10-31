package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import java.net.URI;

/**
 * The type URI of each kind of problem the scheduling context answers with (ADR-057).
 *
 * <p>A kind keeps one URI wherever it is answered, so a client can tell refusals apart without
 * reading their titles. A kind answered in more than one place is written down in each place's own
 * {@code ProblemTypes}, because a context may not reach another's adapter (ADR-008); {@code
 * ProblemTypesTest} holds every constant to its name in kebab case under {@code
 * urn:hrsched:problem:}, which keeps the copies equal. A URI names a kind and is never fetched:
 * nothing is served at it.
 */
final class ProblemTypes {

  static final URI ALREADY_ASSIGNED = URI.create("urn:hrsched:problem:already-assigned");

  static final URI ALREADY_REQUESTED = URI.create("urn:hrsched:problem:already-requested");

  static final URI ASSIGNMENT_REFUSED = URI.create("urn:hrsched:problem:assignment-refused");

  static final URI GENERATION_IS_BUSY = URI.create("urn:hrsched:problem:generation-is-busy");

  static final URI GENERATION_IS_UNAVAILABLE =
      URI.create("urn:hrsched:problem:generation-is-unavailable");

  static final URI INVALID_REQUEST = URI.create("urn:hrsched:problem:invalid-request");

  static final URI MALFORMED_REQUEST = URI.create("urn:hrsched:problem:malformed-request");

  static final URI NO_LONGER_PENDING = URI.create("urn:hrsched:problem:no-longer-pending");

  static final URI NOT_FOUND = URI.create("urn:hrsched:problem:not-found");

  static final URI NOT_PERMITTED = URI.create("urn:hrsched:problem:not-permitted");

  static final URI PUBLISHED_CONCURRENTLY =
      URI.create("urn:hrsched:problem:published-concurrently");

  static final URI REQUEST_TOO_LARGE = URI.create("urn:hrsched:problem:request-too-large");

  static final URI REVISED_CONCURRENTLY = URI.create("urn:hrsched:problem:revised-concurrently");

  static final URI ROSTER_BREAKS_THE_RULES =
      URI.create("urn:hrsched:problem:roster-breaks-the-rules");

  static final URI ROSTER_CANNOT_BE_CHECKED_AGAINST_THE_RULES =
      URI.create("urn:hrsched:problem:roster-cannot-be-checked-against-the-rules");

  static final URI ROSTER_STATUS_FORBIDS_THIS =
      URI.create("urn:hrsched:problem:roster-status-forbids-this");

  private ProblemTypes() {}
}
