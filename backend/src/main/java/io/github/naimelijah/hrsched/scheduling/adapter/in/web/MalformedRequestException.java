package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

/**
 * Raised when a request cannot be understood as sent: it is missing something the use case
 * requires, holds a null where a value is required, or holds a value the system cannot store or
 * compute with, such as an unknown time zone or an instant beyond what the store holds.
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
