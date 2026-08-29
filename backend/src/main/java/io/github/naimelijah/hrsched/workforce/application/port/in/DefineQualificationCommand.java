package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Objects;

/**
 * @param organisation who is defining it
 * @param name what the qualification is called
 */
public record DefineQualificationCommand(OrganisationId organisation, String name) {

  public DefineQualificationCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(name, "name must not be null");
  }
}
