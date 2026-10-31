package io.github.naimelijah.hrsched.notifications.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.UserId;

/**
 * Raised when an actor asks for their notifications but is not somebody one can be addressed to.
 *
 * <p>Notifications are addressed to a personnel record, not to a login. A member who is not an
 * employee — an Admin who administers without working shifts, say — has no inbox, which is
 * different from having an empty one. The platform operator never gets this one: it belongs to no
 * organisation, and is refused as such before anybody asks whose inbox it would be (ADR-030).
 *
 * <p>Its own type in this context rather than something shared, for the reason ADR-008 gives: the
 * kernel stays cheap while it stays small, and a near-twin in another context is a different
 * question about a different concept.
 */
public class NotAddressableException extends RuntimeException {

  private final UserId actor;

  /**
   * @param actor the member who asked, kept for the log and the audit; the message names it by
   *     identity only
   */
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
