package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.Objective;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * @param organisation whose weights
 * @param weights each objective to weigh and its weight; one absent is not weighed. Whether each
 *     weight is acceptable is the domain's to say (R40), so this only copies them
 */
public record ReviseObjectivesCommand(
    OrganisationId organisation, Map<Objective, Integer> weights) {

  public ReviseObjectivesCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(weights, "weights must not be null");
    Map<Objective, Integer> copy = new EnumMap<>(Objective.class);
    copy.putAll(weights);
    weights = Collections.unmodifiableMap(copy);
  }
}
