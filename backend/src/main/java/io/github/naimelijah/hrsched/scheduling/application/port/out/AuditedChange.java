package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.Objects;
import java.util.UUID;

/**
 * One scheduling change, and who made it.
 *
 * <p>The subject is a bare {@link UUID} on purpose. Availability, rosters and names are personal
 * data, and an audit trail that quotes them is a second copy of that data in a place nobody thinks
 * of as a database. A name cannot be put in this record because there is nowhere to put it, which
 * is a stronger guarantee than a convention that it should not be.
 *
 * @param organisation whose data changed; every entry belongs to exactly one tenant
 * @param actor who made the change
 * @param change what kind of change it was
 * @param subject the identifier of what changed. For {@link SchedulingChange#CONSTRAINTS_REVISED}
 *     this is the organisation's own identifier, because a constraint set is one per organisation
 *     and has no identity of its own
 */
public record AuditedChange(
    OrganisationId organisation, UserId actor, SchedulingChange change, UUID subject) {

  public AuditedChange {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(actor, "actor must not be null");
    Objects.requireNonNull(change, "change must not be null");
    Objects.requireNonNull(subject, "subject must not be null");
  }
}
