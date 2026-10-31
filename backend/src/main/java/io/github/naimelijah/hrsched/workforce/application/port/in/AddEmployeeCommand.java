package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Objects;

/**
 * A request to add an employee to an organisation's records.
 *
 * @param organisation who they will work for
 * @param name their name
 */
public record AddEmployeeCommand(OrganisationId organisation, String name) {

  public AddEmployeeCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(name, "name must not be null");
  }
}
