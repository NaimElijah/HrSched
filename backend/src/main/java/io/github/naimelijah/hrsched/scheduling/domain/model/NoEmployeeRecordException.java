package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.UserId;

/**
 * Raised when an actor asks for their own schedule but is not somebody who can be rostered.
 *
 * <p>A user is not the same thing as an employee (ADR-012), and a {@code TenantActor} carries the
 * personnel record it <em>is</em> only when it is one. An Admin who administers the system without
 * working shifts, and the platform operator, both have no such record.
 *
 * <p>A refusal rather than an empty list, because the two mean different things: "you have no
 * personnel record" and "you have one and are rostered for nothing" would otherwise be
 * indistinguishable, and the first is a configuration problem somebody needs to hear about.
 *
 * <p>Kept here rather than in the shared kernel because only this context asks the question, and
 * the kernel stays cheap while it stays small (ADR-008).
 */
public class NoEmployeeRecordException extends RuntimeException {

  private final UserId actor;

  public NoEmployeeRecordException(UserId actor) {
    super(
        "Actor %s has no personnel record, so has no schedule of their own"
            .formatted(actor.value()));
    this.actor = actor;
  }

  /**
   * @return who asked
   */
  public UserId actor() {
    return actor;
  }
}
