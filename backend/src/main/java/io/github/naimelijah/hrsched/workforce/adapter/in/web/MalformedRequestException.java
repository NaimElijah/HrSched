package io.github.naimelijah.hrsched.workforce.adapter.in.web;

/**
 * Raised when a request cannot be understood: a header that is not a UUID or a known role, or a
 * body missing a field the use case requires.
 *
 * <p>Its own type rather than letting {@code NullPointerException} or {@code
 * IllegalArgumentException} escape, because those also arise from genuine defects. Translating them
 * wholesale into 400 would report our own bugs to the caller as their mistake.
 *
 * <p>Deliberately duplicated per web adapter: it is adapter plumbing, and sharing it would create a
 * dependency between the plumbing of two contexts.
 */
class MalformedRequestException extends RuntimeException {

  MalformedRequestException(String message) {
    super(message);
  }
}
