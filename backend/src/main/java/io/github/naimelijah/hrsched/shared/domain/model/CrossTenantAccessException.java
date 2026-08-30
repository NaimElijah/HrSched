package io.github.naimelijah.hrsched.shared.domain.model;

/**
 * Raised when an actor attempts to touch data belonging to an organisation that is not theirs.
 *
 * <p>Deliberately carries no detail about the target beyond its identifier: an error message is
 * itself a channel, and confirming what exists in another tenant is a leak in miniature.
 *
 * <p>Who attempted what is kept as fields rather than only inside the message, because this is the
 * one failure the requirements call a security incident and an incident that can only be found by
 * matching prose is one nobody finds.
 */
public class CrossTenantAccessException extends RuntimeException {

  private final UserId actor;
  private final OrganisationId organisation;

  public CrossTenantAccessException(UserId actor, OrganisationId organisation) {
    super("Actor %s may not access organisation %s".formatted(actor.value(), organisation.value()));
    this.actor = actor;
    this.organisation = organisation;
  }

  /**
   * @return who attempted the access
   */
  public UserId actor() {
    return actor;
  }

  /**
   * @return the organisation they attempted to reach
   */
  public OrganisationId organisation() {
    return organisation;
  }
}
