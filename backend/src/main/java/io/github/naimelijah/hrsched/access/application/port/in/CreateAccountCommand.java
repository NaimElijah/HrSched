package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import java.util.Objects;
import java.util.Optional;

/**
 * What it takes to create an account inside an organisation.
 *
 * <p>The password is temporary by construction: whoever typed it is not its owner, so its owner has
 * to replace it before the account can do anything else (ADR-029).
 *
 * <p>{@link #toString()} shows the role and nothing else. The password is a credential, the sign-in
 * name identifies a person, and the employee link says which person.
 *
 * @param organisation the organisation the account will belong to
 * @param signInName the name it will sign in with, unique across the platform
 * @param temporaryPassword the password it is given, which its owner must replace
 * @param role what it may do in that organisation
 * @param employee the personnel record it is, empty for somebody who is never rostered
 */
public record CreateAccountCommand(
    OrganisationId organisation,
    String signInName,
    String temporaryPassword,
    Role role,
    Optional<EmployeeId> employee) {

  public CreateAccountCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(signInName, "signInName must not be null");
    Objects.requireNonNull(temporaryPassword, "temporaryPassword must not be null");
    Objects.requireNonNull(role, "role must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
  }

  @Override
  public String toString() {
    return "CreateAccountCommand[organisation=%s, role=%s, rest=redacted]"
        .formatted(organisation.value(), role);
  }
}
