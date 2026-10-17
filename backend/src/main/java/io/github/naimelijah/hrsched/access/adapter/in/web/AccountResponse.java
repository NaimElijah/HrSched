package io.github.naimelijah.hrsched.access.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.Role;
import java.time.Instant;
import java.util.UUID;

/**
 * An account as the API returns it to an Admin of its organisation.
 *
 * <p>No password hash and no failure count: one is a credential and the other says more about
 * somebody's typing than about what they may do. {@code locked} carries the only part of that an
 * Admin has to act on.
 *
 * <p>Null rather than {@code Optional} for the employee link, matching {@link SessionResponse}: a
 * client reads an absent member the same way either way.
 *
 * @param id the account
 * @param signInName the name it signs in with
 * @param role what it may do
 * @param employee the personnel record it is, null for somebody who is never rostered, or for an
 *     Employee account disabled until it is linked
 * @param active whether it may sign in and act
 * @param passwordChangeRequired whether it still holds a password somebody else chose
 * @param locked whether consecutive failures have locked it
 * @param createdAt when it was created
 */
public record AccountResponse(
    UUID id,
    String signInName,
    Role role,
    UUID employee,
    boolean active,
    boolean passwordChangeRequired,
    boolean locked,
    Instant createdAt) {}
