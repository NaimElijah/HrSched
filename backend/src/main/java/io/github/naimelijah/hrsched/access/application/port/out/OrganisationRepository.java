package io.github.naimelijah.hrsched.access.application.port.out;

import io.github.naimelijah.hrsched.access.domain.model.Organisation;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.Optional;

/**
 * Driven port: storing the organisations the platform serves.
 *
 * <p>Its reads are not scoped to a tenant, because it <em>is</em> the register of tenants.
 * Elsewhere a lookup takes an {@code OrganisationId} so a cross-tenant read cannot be written by
 * forgetting a filter (ADR-009); here there is no outer scope to filter by. {@link #findAll()}
 * returning every customer is the point of the method rather than a leak, and the identity {@link
 * #findById} takes is the organisation asked about, not a scope. ADR-055 lists these with the other
 * reads in access that take no organisation.
 *
 * <p>What keeps that safe is the use case above it: listing is refused to anyone who belongs to a
 * tenant, and reading one is refused unless it is the reader's own organisation or the reader is
 * the operator. Do not add a caller that skips it.
 */
public interface OrganisationRepository {

  void save(Organisation organisation);

  Optional<Organisation> findById(OrganisationId id);

  /**
   * @return every organisation on the platform; operator-only at the use case above
   */
  List<Organisation> findAll();
}
