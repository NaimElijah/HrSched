package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.Objects;
import java.util.Optional;

/**
 * What an account becomes inside its organisation.
 *
 * <p>Every field is an identifier or an enum, so this one prints in full: there is nothing here
 * that says who anybody is.
 *
 * @param organisation the organisation the account belongs to
 * @param account which account to change
 * @param role what it may do from now on
 * @param employee the personnel record it is, empty to unlink it, which the Employee role refuses
 *     (R34)
 */
public record ChangeMembershipCommand(
    OrganisationId organisation, UserId account, Role role, Optional<EmployeeId> employee) {

  /**
   * @throws NullPointerException if {@code organisation}, {@code account}, {@code role} or {@code
   *     employee} is null
   */
  public ChangeMembershipCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(account, "account must not be null");
    Objects.requireNonNull(role, "role must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
  }
}
