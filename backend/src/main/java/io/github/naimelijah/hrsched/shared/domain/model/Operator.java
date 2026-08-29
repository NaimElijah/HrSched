package io.github.naimelijah.hrsched.shared.domain.model;

import java.util.Objects;

/**
 * The platform operator: belongs to no organisation and may reach every one of them.
 *
 * <p>This is the most dangerous identity in the system, which is precisely why it is a named type
 * rather than an implicit privilege held by whoever has database access. Every cross-tenant read in
 * the product traces back to this class, so there is exactly one place to audit.
 *
 * @param id who is acting
 */
public record Operator(UserId id) implements Actor {

  public Operator {
    Objects.requireNonNull(id, "id must not be null");
  }

  @Override
  public boolean mayAccess(OrganisationId organisation) {
    return true;
  }
}
