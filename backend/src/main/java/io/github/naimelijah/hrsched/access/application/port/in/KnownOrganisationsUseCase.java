package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;

/**
 * Access's published API: whether a claimed tenant is a real one.
 *
 * <p>The one entry point other contexts, and the composition root, are permitted to use.
 *
 * <p><strong>This port deliberately takes no {@link Actor}, and it is the only one here that does
 * not.</strong> Every other use case in this repository is handed the actor first and checks it
 * before doing anything. This one runs <em>before</em> an actor is trustworthy at all: it is the
 * question "is the organisation this caller claims a real organisation?", asked at the boundary,
 * and an actor built from an unverified claim is not evidence of anything. Requiring one would be
 * circular.
 *
 * <p>It leaks nothing an attacker does not already control: the caller supplies the identifier and
 * learns only a boolean, which the boundary turns into the same 404 an unknown record produces. See
 * ADR-020.
 */
public interface KnownOrganisationsUseCase {

  /**
   * @param organisation the organisation identifier a caller has claimed
   * @return whether an organisation with that identity has been onboarded
   */
  boolean isKnown(OrganisationId organisation);
}
