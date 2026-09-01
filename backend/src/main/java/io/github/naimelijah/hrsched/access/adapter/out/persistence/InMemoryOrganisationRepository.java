package io.github.naimelijah.hrsched.access.adapter.out.persistence;

import io.github.naimelijah.hrsched.access.application.port.out.OrganisationRepository;
import io.github.naimelijah.hrsched.access.domain.model.Organisation;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing organisations in memory.
 *
 * <p>PLACEHOLDER until ADR-004. Unlike its siblings there is no organisation to scope a read by:
 * this is the register of tenants, so {@link #findAll()} is meant to cross every one of them and
 * the guard is the use case above it.
 *
 * <p>The consequence of holding the tenant register in memory is worth stating plainly: a restart
 * empties it, and every organisation that existed becomes unknown, which after ADR-020 means every
 * request from every tenant starts answering 404.
 */
@Repository
public class InMemoryOrganisationRepository implements OrganisationRepository {

  private final Map<OrganisationId, Organisation> byId = new ConcurrentHashMap<>();

  @Override
  public void save(Organisation organisation) {
    byId.put(organisation.id(), organisation);
  }

  @Override
  public Optional<Organisation> findById(OrganisationId id) {
    return Optional.ofNullable(byId.get(id));
  }

  @Override
  public List<Organisation> findAll() {
    return byId.values().stream().sorted(Comparator.comparing(Organisation::name)).toList();
  }
}
