package io.github.naimelijah.hrsched.shared.domain.model;

import java.util.Objects;
import java.util.Set;

/**
 * A user acting within their own organisation, in one {@link Role}.
 *
 * @param id who is acting
 * @param organisation the single organisation they belong to
 * @param role what they may do within it
 */
public record TenantActor(UserId id, OrganisationId organisation, Role role) implements Actor {

  public TenantActor {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(role, "role must not be null");
  }

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
