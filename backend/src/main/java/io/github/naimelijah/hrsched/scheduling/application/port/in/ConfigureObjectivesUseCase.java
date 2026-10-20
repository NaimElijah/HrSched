package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.InvalidObjectiveWeightException;
import io.github.naimelijah.hrsched.scheduling.domain.model.ObjectiveWeights;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.CrossTenantAccessException;
import io.github.naimelijah.hrsched.shared.domain.model.NotPermittedException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;

/**
 * Driving port: how an organisation weights what makes one legal roster better than another (F35,
 * F36, ADR-010).
 */
public interface ConfigureObjectivesUseCase {

  /**
   * Replaces the organisation's weights with these (F35). An objective left out is no longer
   * weighed.
   *
   * @param actor an Admin of that organisation
   * @param command the weights that apply from now on
   * @return them, as stored
   * @throws NotPermittedException if the actor is not an Admin
   * @throws CrossTenantAccessException if the actor belongs to another organisation
   * @throws InvalidObjectiveWeightException if a weight is below one (R40)
   */
  ObjectiveWeights reviseObjectives(Actor actor, ReviseObjectivesCommand command);

  /**
   * Reads the organisation's weights (F36). Any member may, as any member may read the rules.
   *
   * @param actor a member of that organisation
   * @param organisation whose weights to read
   * @return its weights; none, if it has weighted nothing yet
   * @throws CrossTenantAccessException if the actor belongs to another organisation
   */
  ObjectiveWeights findObjectives(Actor actor, OrganisationId organisation);
}
