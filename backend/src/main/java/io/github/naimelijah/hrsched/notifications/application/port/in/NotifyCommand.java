package io.github.naimelijah.hrsched.notifications.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Objects;
import java.util.UUID;

/**
 * @param organisation whose notification this is
 * @param recipient the personnel record being told
 * @param kind what happened
 * @param subject the identifier of the thing it happened to
 */
public record NotifyCommand(
    OrganisationId organisation, EmployeeId recipient, NotifiableEvent kind, UUID subject) {

  public NotifyCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(recipient, "recipient must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
    Objects.requireNonNull(subject, "subject must not be null");
  }
}
