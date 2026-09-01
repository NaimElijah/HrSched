package io.github.naimelijah.hrsched.access.application.port.out;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.Objects;
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
 * @param organisation the organisation that was changed, which for an onboarding is the new one
 * @param actor who made the change
 * @param change what kind of change it was
 * @param subject the identifier of what changed, which today is always the organisation itself
 */
public record AuditedChange(
    OrganisationId organisation, UserId actor, AccessChange change, UUID subject) {

  public AuditedChange {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(actor, "actor must not be null");
    Objects.requireNonNull(change, "change must not be null");
    Objects.requireNonNull(subject, "subject must not be null");
  }
}
