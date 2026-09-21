package io.github.naimelijah.hrsched.access.domain.model;

/**
 * What an account is a member of: exactly one organisation, or the platform itself.
 *
 * <p>Sealed, and the reason is the same one that seals {@code Actor}. An account either belongs to
 * a tenant or administers the register of tenants, and there is no third thing; keeping the
 * alternatives closed means the two cases are enumerated in one place, and a new one cannot appear
 * without editing this file.
 *
 * <p>The distinction is structural rather than a flag, so the impossible combinations cannot be
 * written down: an operator carries no role and no employee link, because {@link
 * OperatorMembership} has nowhere to put them.
 */
public sealed interface Membership permits TenantMembership, OperatorMembership {}
