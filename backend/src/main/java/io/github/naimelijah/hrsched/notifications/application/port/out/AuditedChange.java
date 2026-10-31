package io.github.naimelijah.hrsched.notifications.application.port.out;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.UserId;
import java.util.Objects;
import java.util.UUID;

/**
 * One notification change, and who made it.
 *
 * <p>The fourth record of this name, one per context, and deliberately not shared with the other
 * three: they carry different change vocabularies, so they are different types rather than
 * duplication (ADR-008). The collision is visible only in {@code UseCaseConfiguration}.
 *
 * <p>The subject is the notification, not its recipient. Who was told what is in the store; the
 * trail answers who caused an entry to exist.
 *
 * @param organisation whose data changed
 * @param actor who caused the change
 * @param change what kind of change it was
 * @param subject the identifier of the notification that changed
 */
public record AuditedChange(
    OrganisationId organisation, UserId actor, NotificationChange change, UUID subject) {

  /**
   * @throws NullPointerException if any component is null
   */
  public AuditedChange {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(actor, "actor must not be null");
    Objects.requireNonNull(change, "change must not be null");
    Objects.requireNonNull(subject, "subject must not be null");
  }
}
