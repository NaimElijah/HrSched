package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.WorkingTimeConstraint;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.Objects;

/**
 * A request to replace an organisation's working-time rules with a new version.
 *
 * @param organisation whose rules
 * @param constraints the rules that should apply, replacing the previous version entirely
 */
public record ReviseConstraintsCommand(
    OrganisationId organisation, List<WorkingTimeConstraint> constraints) {

  public ReviseConstraintsCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    constraints = List.copyOf(constraints);
  }
}
