package io.github.naimelijah.hrsched.shared.domain.model;

/**
 * Raised when the platform operator tries to act inside an organisation.
 *
 * <p>A different refusal from {@link CrossTenantAccessException}, and deliberately so. That one
 * means "you belong to a tenant, but not that one", and is answered with a 404 because saying
 * otherwise would confirm the organisation exists to somebody who has no business knowing. This one
 * means "you belong to no tenant at all", and is answered with a 403: the operator already knows
 * which organisations exist, because listing them is their job, so there is nothing left to hide.
 *
 * <p>The operator administers the register of organisations and nothing inside one (ADR-030). If
 * support access to a tenant's data is ever needed, it is its own feature with its own audit trail
 * rather than a privilege that was there all along.
 */
public class NotATenantMemberException extends RuntimeException {

  private final UserId actor;

  /**
   * Creates the refusal for an actor that belongs to no organisation.
   *
   * @param actor who attempted it
   * @throws NullPointerException if {@code actor} is {@code null}, because the message is built
   *     from it
   */
  public NotATenantMemberException(UserId actor) {
    super(
        "Actor %s belongs to no organisation and may not act inside one".formatted(actor.value()));
    this.actor = actor;
  }

  /**
   * @return who attempted it
   */
  public UserId actor() {
    return actor;
  }
}
