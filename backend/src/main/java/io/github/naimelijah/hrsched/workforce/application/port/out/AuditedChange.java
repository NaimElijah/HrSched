package io.github.naimelijah.hrsched.workforce.application.port.out;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.Objects;
import java.util.UUID;

/**
 * One workforce change, and who made it.
 *
 * <p>The subject is a bare {@link UUID} on purpose. Names, availability and absence are personal
 * data, and an audit trail that quotes them is a second copy of that data in a place nobody thinks
 * of as a database. A name cannot be put in this record because there is nowhere to put it, which
 * is a stronger guarantee than a convention that it should not be.
 *
 * <p>A near-twin of scheduling's record of the same name, and deliberately not shared with it: the
 * two carry different change vocabularies, so they are different types rather than duplication, and
 * anything common would have to live in the kernel ADR-008 keeps small.
 *
 * @param organisation whose data changed; every entry belongs to exactly one tenant
 * @param actor who made the change - the acting user, never the employee they acted on
 * @param change what kind of change it was
 * @param subject the identifier of the record that changed, which for every change but {@link
 *     WorkforceChange#QUALIFICATION_DEFINED} is an employee
 */
public record AuditedChange(
    OrganisationId organisation, UserId actor, WorkforceChange change, UUID subject) {

  public AuditedChange {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(actor, "actor must not be null");
    Objects.requireNonNull(change, "change must not be null");
    Objects.requireNonNull(subject, "subject must not be null");
  }
}
