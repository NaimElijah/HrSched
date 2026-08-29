package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import io.github.naimelijah.hrsched.scheduling.application.port.out.ConstraintSetRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing the current constraint set per organisation, in memory.
 *
 * <p>PLACEHOLDER until ADR-004, and knowingly incomplete: it keeps only the latest version. A
 * published roster records the version that approved it, so historical versions have to be
 * retrievable before that record means anything. Owed alongside real persistence.
 */
@Repository
public class InMemoryConstraintSetRepository implements ConstraintSetRepository {

  private final Map<OrganisationId, ConstraintSet> current = new ConcurrentHashMap<>();

  @Override
  public void save(ConstraintSet constraintSet) {
    current.put(constraintSet.organisation(), constraintSet);
  }

  @Override
  public Optional<ConstraintSet> findCurrent(OrganisationId organisation) {
    return Optional.ofNullable(current.get(organisation));
  }
}
