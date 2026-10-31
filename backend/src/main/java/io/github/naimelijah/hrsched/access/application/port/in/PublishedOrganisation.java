package io.github.naimelijah.hrsched.access.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Objects;

/**
 * What a caller is told about an organisation: its identity and its name.
 *
 * <p>Deliberately not the {@code Organisation} aggregate, for the same reason {@code
 * RosterableEmployee} is not {@code Employee} — what a context publishes is a contract it intends
 * to keep, not whatever its model happens to hold today.
 *
 * @param id the organisation's identity, which is also its tenant boundary
 * @param name what it is called
 */
public record PublishedOrganisation(OrganisationId id, String name) {

  /**
   * @throws NullPointerException if {@code id} or {@code name} is null
   */
  public PublishedOrganisation {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(name, "name must not be null");
  }
}
