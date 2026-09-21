package io.github.naimelijah.hrsched.access.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import java.util.Objects;
import java.util.Optional;

/**
 * Membership of one organisation, in one role, optionally as one of its employees.
 *
 * <p>One organisation and one role, not a set of either: an account that could act in two tenants
 * would make every tenant-scoped query ask which one, and that is the question this product does
 * not want to have to answer. Somebody who works for two customers holds two accounts.
 *
 * <p>The employee link is product data rather than authentication, and is deliberately not an
 * assumption that the two identifiers match (ADR-012). It is empty for somebody who is never
 * rostered — an administrator who does not work shifts.
 *
 * @param organisation the single organisation this account belongs to
 * @param role what it may do within that organisation
 * @param employee the personnel record it is, empty for an account that is not rostered
 */
public record TenantMembership(
    OrganisationId organisation, Role role, Optional<EmployeeId> employee) implements Membership {

  public TenantMembership {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(role, "role must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
  }
}
