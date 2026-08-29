package io.github.naimelijah.hrsched.shared.domain.model;

/**
 * Whoever is performing an operation.
 *
 * <p>Sealed on purpose. Tenant isolation is the property whose failure is a security incident, so
 * the set of things that can cross an organisation boundary is closed and visible in one place:
 * {@link TenantActor} can reach exactly its own organisation, {@link Operator} can reach any. A
 * third possibility cannot be added without editing this file.
 *
 * <p>Carries no credentials. Authentication is not modelled yet; an actor is the *result* of
 * authentication, and this type describes only what that result permits.
 */
public sealed interface Actor permits TenantActor, Operator {

  /**
   * @return the identity of whoever is acting, never {@code null}
   */
  UserId id();

  /**
   * @param organisation the organisation whose data is about to be touched
   * @return whether this actor may touch it
   */
  boolean mayAccess(OrganisationId organisation);

  /**
   * Guards an operation against cross-tenant access.
   *
   * @param organisation the organisation whose data is about to be touched
   * @throws CrossTenantAccessException if this actor may not touch it
   */
  default void requireAccessTo(OrganisationId organisation) {
    if (!mayAccess(organisation)) {
      throw new CrossTenantAccessException(id(), organisation);
    }
  }
}
