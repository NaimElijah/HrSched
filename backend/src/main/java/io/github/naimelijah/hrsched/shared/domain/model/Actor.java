package io.github.naimelijah.hrsched.shared.domain.model;

/**
 * Whoever is performing an operation.
 *
 * <p>Sealed on purpose. Tenant isolation is the property whose failure is a security incident, so
 * the set of things that can cross an organisation boundary is closed and visible in one place:
 * {@link TenantActor} can reach exactly its own organisation, {@link Operator} can reach any. A
 * third possibility cannot be added without editing this file.
 *
 * <p>Carries no credentials. An actor is the <em>result</em> of authentication, and this type
 * describes only what that result permits.
 */
public sealed interface Actor permits TenantActor, Operator {

  /**
   * @return the identity of whoever is acting, never {@code null}
   */
  UserId id();

  /**
   * @param organisation the organisation whose data is about to be touched
   * @return whether this actor may act on that organisation's data; never true for an {@link
   *     Operator}, which belongs to no organisation and acts inside none (ADR-030)
   */
  boolean mayAccess(OrganisationId organisation);

  /**
   * Hands back the member this actor is, whichever organisation that turns out to be.
   *
   * <p>The question {@link #requireAccessTo} asks minus the organisation, and it exists because a
   * web adapter has to ask it in the other order: the route acts in whatever organisation the
   * caller belongs to, so there is no identifier to check against — the caller's own membership
   * <em>is</em> the answer. That is strictly safer than naming one, because there is then nothing
   * in the request for anybody to change.
   *
   * @return this actor as a member of their own organisation
   * @throws NotATenantMemberException if this actor belongs to no organisation at all, which is the
   *     platform operator and is exactly the refusal ADR-030 describes
   */
  default TenantActor requireTenantMembership() {
    return switch (this) {
      case TenantActor tenant -> tenant;
      case Operator operator -> throw new NotATenantMemberException(id());
    };
  }

  /**
   * Guards an operation on an organisation's data, and hands back the member who may perform it.
   *
   * <p><strong>Returning the {@link TenantActor} is the point.</strong> The guard used to be a
   * statement, and every caller followed it with {@code if (actor instanceof TenantActor t)} before
   * checking a role — an idiom that silently <em>skipped</em> the role check for an operator, so
   * the one identity that belongs to no tenant could do anything inside every tenant. Handing back
   * the member means the next line has something to ask a role of, and there is nothing left to
   * skip.
   *
   * <p>A {@code switch} over the sealed type rather than a boolean, so that a third kind of actor
   * cannot be added without deciding, here, what it may reach.
   *
   * @param organisation the organisation whose data is about to be touched
   * @return this actor as a member of that organisation
   * @throws CrossTenantAccessException if this actor belongs to a different organisation
   * @throws NotATenantMemberException if this actor belongs to no organisation at all
   */
  default TenantActor requireAccessTo(OrganisationId organisation) {
    return switch (this) {
      case TenantActor tenant -> {
        if (!tenant.mayAccess(organisation)) {
          throw new CrossTenantAccessException(id(), organisation);
        }
        yield tenant;
      }
      case Operator operator -> throw new NotATenantMemberException(id());
    };
  }
}
