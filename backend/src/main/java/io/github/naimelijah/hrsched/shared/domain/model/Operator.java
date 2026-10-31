package io.github.naimelijah.hrsched.shared.domain.model;

import java.util.Objects;

/**
 * The platform operator: belongs to no organisation, and administers the register of them.
 *
 * <p>This is the most privileged identity in the system, which is precisely why it is a named type
 * rather than an implicit privilege held by whoever has database access: there is exactly one place
 * to audit.
 *
 * <p>It is also <strong>confined</strong>. An operator onboards, lists and reads organisations, and
 * may not act inside one — not because every use case remembers to refuse it, but because {@link
 * Actor#requireAccessTo} cannot hand back a member for an actor that is not one (ADR-030). Support
 * access to a tenant's data, if it is ever needed, is its own feature with its own audit trail
 * rather than a privilege that was always quietly there.
 *
 * @param id who is acting
 * @throws NullPointerException if {@code id} is {@code null}
 */
public record Operator(UserId id) implements Actor {

  public Operator {
    Objects.requireNonNull(id, "id must not be null");
  }

  /**
   * Always false. An operator has no organisation, so there is none whose data it may act on; the
   * one thing it reads across tenants is the register itself, which {@code OrganisationService}
   * permits explicitly rather than by this returning true.
   */
  @Override
  public boolean mayAccess(OrganisationId organisation) {
    return false;
  }
}
