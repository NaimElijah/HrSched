package io.github.naimelijah.hrsched.notifications.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.UserId;

/**
 * Raised when an actor asks for their notifications but is not somebody one can be addressed to.
 *
 * <p>Notifications are addressed to a personnel record, not to a login. A user who is not an
 * employee — an Admin who administers without working shifts, or the platform operator — has no
 * inbox, which is different from having an empty one.
 *
 * <p>Its own type in this context rather than something shared, for the reason ADR-008 gives: the
 * kernel stays cheap while it stays small, and a near-twin in another context is a different
 * question about a different concept.
 */
public class NotAddressableException extends RuntimeException {

  private final UserId actor;

  public NotAddressableException(UserId actor) {
    super("Actor %s has no personnel record, so has no notifications".formatted(actor.value()));
    this.actor = actor;
  }

  /**
   * @return who asked
   */
  public UserId actor() {
    return actor;
  }
}
