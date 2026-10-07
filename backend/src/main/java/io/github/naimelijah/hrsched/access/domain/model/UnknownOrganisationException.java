package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;

/**
 * Raised when no organisation with the given identity is on the platform.
 *
 * <p>Answered exactly as a tenant naming somebody else's organisation is, so that the two cannot be
 * told apart (ADR-009).
 */
public class UnknownOrganisationException extends RuntimeException {

  /**
   * @param organisation the organisation that was named
   */
  public UnknownOrganisationException(OrganisationId organisation) {
    super("No such organisation: %s".formatted(organisation.value()));
  }
}
