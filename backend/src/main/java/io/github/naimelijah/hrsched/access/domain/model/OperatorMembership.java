package io.github.naimelijah.hrsched.access.domain.model;

/**
 * The membership of the platform operator: no organisation, no role, no employee record.
 *
 * <p>A record with no components rather than an enum constant, so that it sits beside {@link
 * TenantMembership} as one of two alternatives of the same sealed type. Every instance is equal to
 * every other, which is correct: there is nothing to tell two of them apart.
 */
public record OperatorMembership() implements Membership {}
