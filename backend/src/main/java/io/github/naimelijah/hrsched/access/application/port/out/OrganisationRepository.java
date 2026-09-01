package io.github.naimelijah.hrsched.access.application.port.out;

import io.github.naimelijah.hrsched.access.domain.model.Organisation;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.Optional;

/**
 * Driven port: storing the organisations the platform serves.
 *
 * <p>The one repository in this codebase whose reads are not scoped to a tenant, because it
 * <em>is</em> the register of tenants. Everywhere else a lookup takes an {@code OrganisationId} so
 * a cross-tenant read cannot be written by forgetting a filter (ADR-009); here there is no outer
 * scope to filter by, and {@link #findAll()} returning every customer is the point of the method
 * rather than a leak.
 *
 * <p>What keeps that safe is the use case above it, where listing is refused to anyone who belongs
 * to a tenant. Do not add a caller that skips it.
 */
public interface OrganisationRepository {

  void save(Organisation organisation);

  Optional<Organisation> findById(OrganisationId id);

  /**
   * @return every organisation on the platform; operator-only at the use case above
   */
  List<Organisation> findAll();
}
