package io.github.naimelijah.hrsched.shared.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A user acting within their own organisation, in one {@link Role}.
 *
 * <p>A user is not the same thing as an employee — see {@link UserId} — so the two are linked
 * explicitly rather than by assuming the identifiers match. Without that link a person cannot be
 * recognised as the subject of their own record, and self-service (requirement F6) is
 * unimplementable. The link is product data: it is needed whichever identity provider is eventually
 * chosen, and it does not belong to authentication.
 *
 * @param id who is acting
 * @param organisation the single organisation they belong to
 * @param role what they may do within it
 * @param employee the personnel record they are, empty for a user who is not rostered
 */
public record TenantActor(
    UserId id, OrganisationId organisation, Role role, Optional<EmployeeId> employee)
    implements Actor {

  public TenantActor {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(role, "role must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
  }

  /**
   * Creates a member who is not rostered, so {@code employee} is empty.
   *
   * @param id who is acting
   * @param organisation the single organisation they belong to
   * @param role what they may do within it
   */
  public TenantActor(UserId id, OrganisationId organisation, Role role) {
    this(id, organisation, role, Optional.empty());
  }

  /**
   * Tests whether this actor is the person a personnel record belongs to.
   *
   * <p>False for an actor with no linked employee. The identities are compared through the carried
   * link, never by matching {@link UserId} against {@link EmployeeId} (ADR-012).
   *
   * @param subject the personnel record being acted on
   * @return whether this actor is that person
   * @throws NullPointerException if {@code subject} is {@code null}
   */
  public boolean isThemself(EmployeeId subject) {
    Objects.requireNonNull(subject, "subject must not be null");
    return employee.filter(subject::equals).isPresent();
  }

  /** Exactly their own organisation, and no other. */
  @Override
  public boolean mayAccess(OrganisationId other) {
    return organisation.equals(other);
  }

  /**
   * Guards an operation against the wrong role.
   *
   * @param permitted the roles allowed to perform the operation
   * @throws NotPermittedException if this actor holds none of them
   */
  public void requireRoleIn(Set<Role> permitted) {
    if (!permitted.contains(role)) {
      throw new NotPermittedException(id, role, permitted);
    }
  }
}
