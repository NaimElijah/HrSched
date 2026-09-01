package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.ConstraintSetRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Hand-written, like every other double here: the repository is our own port. */
final class FakeConstraintSets implements ConstraintSetRepository {

  private final Map<OrganisationId, ConstraintSet> stored = new HashMap<>();

  @Override
  public void save(ConstraintSet constraintSet) {
    stored.put(constraintSet.organisation(), constraintSet);
  }

  @Override
  public Optional<ConstraintSet> findCurrent(OrganisationId organisation) {
    return Optional.ofNullable(stored.get(organisation));
  }
}
