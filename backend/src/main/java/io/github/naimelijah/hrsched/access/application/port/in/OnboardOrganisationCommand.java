package io.github.naimelijah.hrsched.access.application.port.in;

import java.util.Objects;

/**
 * @param name what the organisation is called
 */
public record OnboardOrganisationCommand(String name) {

  public OnboardOrganisationCommand {
    Objects.requireNonNull(name, "name must not be null");
  }
}
