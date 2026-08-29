package io.github.naimelijah.hrsched.shared.domain.model;

/**
 * Raised when an actor attempts to touch data belonging to an organisation that is not theirs.
 *
 * <p>Deliberately carries no detail about the target beyond its identifier: an error message is
 * itself a channel, and confirming what exists in another tenant is a leak in miniature.
 */
public class CrossTenantAccessException extends RuntimeException {

  public CrossTenantAccessException(UserId actor, OrganisationId organisation) {
    super("Actor %s may not access organisation %s".formatted(actor.value(), organisation.value()));
  }
}
