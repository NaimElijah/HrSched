package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;

/**
 * Driving port: setting the working-time rules an organisation is bound by.
 *
 * <p>This is where Q4 is answered in practice. Nothing here mentions a country: an Admin supplies
 * thresholds, and a jurisdiction is simply a preset someone loads.
 */
public interface ConfigureConstraintsUseCase {

  /**
   * @param actor whoever is configuring them; must be an Admin of the organisation
   * @param command the rules that should apply from now on
   * @return the newly created version
   */
  ConstraintSet reviseConstraints(Actor actor, ReviseConstraintsCommand command);

  /**
   * @param actor whoever is asking
   * @param organisation whose rules to read
   * @return the current rules, creating an empty first version if none exist
   */
  ConstraintSet currentConstraints(Actor actor, OrganisationId organisation);
}
