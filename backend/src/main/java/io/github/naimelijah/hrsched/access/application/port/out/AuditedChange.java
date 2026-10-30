package io.github.naimelijah.hrsched.access.application.port.out;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One access change, and who made it.
 *
 * <p>One of four records of this name, one per context, and deliberately not shared with the
 * others: they carry different change vocabularies, so they are different types rather than
 * duplication, and anything common would have to live in the kernel ADR-008 keeps small. The
 * collision is visible only in {@code UseCaseConfiguration}, which is that class's job.
 *
 * <p>The subject is a bare {@link UUID} on purpose, as in its siblings — an organisation's name is
 * not personal data, but the record has nowhere to put a name and does not acquire one by
 * exception.
 *
 * <p>The organisation is optional because a change to the platform operator's own account, or to
 * one of its sessions, belongs to no tenant: the operator is a member of none, which is the whole
 * point of it. Empty means "no organisation", never "not recorded", and the adapter leaves the
 * field out entirely rather than writing a null a query could not tell apart from a missing one.
 *
 * @param organisation the organisation the change belongs to: the new one for an onboarding, the
 *     account's own for a change to an account or a session, and empty for the platform operator's
 * @param actor who made the change
 * @param change what kind of change it was
 * @param subject the identifier of what changed, which depends on the change: the organisation for
 *     {@link AccessChange#ORGANISATION_ONBOARDED}; the session for {@link AccessChange#SIGNED_IN},
 *     {@link AccessChange#SIGNED_OUT} and {@link AccessChange#REFRESH_TOKEN_REUSED}; and the
 *     account for every other change, {@link AccessChange#ACCOUNT_CREATED}, {@link
 *     AccessChange#ACCOUNT_MEMBERSHIP_CHANGED}, {@link AccessChange#ACCOUNT_DISABLED}, {@link
 *     AccessChange#ACCOUNT_ENABLED}, {@link AccessChange#ACCOUNT_PASSWORD_RESET}, {@link
 *     AccessChange#OPERATOR_BOOTSTRAPPED}, {@link AccessChange#ACCOUNT_LOCKED} and {@link
 *     AccessChange#PASSWORD_CHANGED}
 */
public record AuditedChange(
    Optional<OrganisationId> organisation, UserId actor, AccessChange change, UUID subject) {

  public AuditedChange {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(actor, "actor must not be null");
    Objects.requireNonNull(change, "change must not be null");
    Objects.requireNonNull(subject, "subject must not be null");
  }
}
