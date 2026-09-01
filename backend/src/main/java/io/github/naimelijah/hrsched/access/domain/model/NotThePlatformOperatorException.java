package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.UserId;

/**
 * Raised when a tenant user attempts something only the platform operator may do.
 *
 * <p>The inverse of {@code NotPermittedException}, and a separate type because it is a different
 * question. That one asks which {@code Role} a user holds inside their organisation; this one asks
 * whether the actor belongs to an organisation at all, and a tenant Admin fails it however
 * privileged they are within their own walls.
 *
 * <p>It lives here rather than in the shared kernel because only this context asks the question,
 * and the kernel stays cheap only while it stays small (ADR-008).
 *
 * <p>Keeps who attempted it as a field, so a refusal can be counted and searched for rather than
 * only read.
 */
public class NotThePlatformOperatorException extends RuntimeException {

  private final UserId actor;

  /**
   * @param actor who attempted the operation
   */
  public NotThePlatformOperatorException(UserId actor) {
    super("Actor %s is not the platform operator".formatted(actor.value()));
    this.actor = actor;
  }

  /**
   * @return who attempted the operation
   */
  public UserId actor() {
    return actor;
  }
}
