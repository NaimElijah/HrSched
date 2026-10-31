package io.github.naimelijah.hrsched.configuration.web;

import java.net.URI;

/**
 * The type URI of each kind of problem answered outside the contexts: by the security chain, by
 * request binding, by the filters in front of the controllers, and by {@link
 * RequestRoutingExceptionHandler} for the refusals Spring MVC makes on its own (ADR-057).
 *
 * <p>A kind keeps one URI wherever it is answered, so a client can tell refusals apart without
 * reading their titles. A kind answered in more than one place is written down in each place's own
 * {@code ProblemTypes}, because a context may not reach another's adapter (ADR-008); {@code
 * ProblemTypesTest} holds every constant to its name in kebab case under {@code
 * urn:hrsched:problem:}, which keeps the copies equal. A URI names a kind and is never fetched:
 * nothing is served at it.
 */
public final class ProblemTypes {

  /** A request Spring could not bind to a handler's parameters, answered 400. */
  public static final URI INVALID_REQUEST = URI.create("urn:hrsched:problem:invalid-request");

  /** A mapped path asked in a method none of its handlers takes, answered 405. */
  public static final URI METHOD_NOT_ALLOWED = URI.create("urn:hrsched:problem:method-not-allowed");

  /** An {@code Accept} naming no media type the route can answer in, answered 406. */
  public static final URI NOT_ACCEPTABLE = URI.create("urn:hrsched:problem:not-acceptable");

  /** A path no handler maps, answered 404. */
  public static final URI NOT_FOUND = URI.create("urn:hrsched:problem:not-found");

  /** A caller whose role does not allow the route, answered 403. */
  public static final URI NOT_PERMITTED = URI.create("urn:hrsched:problem:not-permitted");

  /** A caller who must change a temporary password before doing anything else, answered 403. */
  public static final URI PASSWORD_CHANGE_REQUIRED =
      URI.create("urn:hrsched:problem:password-change-required");

  /** A request without an access token this service accepts, answered 401. */
  public static final URI UNAUTHENTICATED = URI.create("urn:hrsched:problem:unauthenticated");

  /** A body of a type the route does not read, or JSON declared in a charset but UTF-8, 415. */
  public static final URI UNSUPPORTED_MEDIA_TYPE =
      URI.create("urn:hrsched:problem:unsupported-media-type");

  private ProblemTypes() {}
}
