package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.Objects;

/**
 * A request to generate a draft roster.
 *
 * @param organisation whose roster
 * @param period the span to cover
 */
public record GenerateRosterCommand(OrganisationId organisation, TimeRange period) {

  public GenerateRosterCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(period, "period must not be null");
  }
}
