package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;

/**
 * Raised when no organisation with the given identity is on the platform.
 *
 * <p>Only the platform operator can meet it: a tenant naming any organisation but its own is
 * refused by the tenant guard first. It is answered with the same problem body as that refusal
 * anyway, so a caller handles one shape of absence rather than two.
 */
public class UnknownOrganisationException extends RuntimeException {

  /**
   * @param organisation the organisation that was named
   */
  public UnknownOrganisationException(OrganisationId organisation) {
    super("No such organisation: %s".formatted(organisation.value()));
  }
}
