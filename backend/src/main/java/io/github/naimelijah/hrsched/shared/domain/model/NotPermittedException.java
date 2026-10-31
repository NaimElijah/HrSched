package io.github.naimelijah.hrsched.shared.domain.model;

import java.util.Set;

/**
 * Raised when an actor holds the wrong role for an operation.
 *
 * <p>Keeps who and which role as fields, so a refusal can be counted and searched for rather than
 * only read.
 */
public class NotPermittedException extends RuntimeException {

  private final UserId actor;
  private final Role held;

  /**
   * Creates the refusal for an actor whose role is not one the operation allows.
   *
   * @param actor who attempted the operation
   * @param held the role they hold
   * @param permitted the roles that would have been enough; named in the message and not kept
   * @throws NullPointerException if {@code actor} is {@code null}, because the message is built
   *     from it
   */
  public NotPermittedException(UserId actor, Role held, Set<Role> permitted) {
    super(
        "Actor %s holds %s, but this requires one of %s".formatted(actor.value(), held, permitted));
    this.actor = actor;
    this.held = held;
  }

  /**
   * @return who attempted the operation
   */
  public UserId actor() {
    return actor;
  }

  /**
   * @return the role they hold, which was not one of those permitted
   */
  public Role held() {
    return held;
  }
}
