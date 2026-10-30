package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.ConstraintSetRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintsRevisedConcurrentlyException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Hand-written, like every other double here: the repository is our own port.
 *
 * <p>Keeps every version, as {@code JdbcConstraintSetRepository} does, so that a test can revise
 * the rules and still read back the version an earlier roster was published under (Q9). Refuses a
 * version already stored, as {@code JdbcConstraintSetRepository} does, with the conflict the port
 * declares: a fake that overwrote it would let a revision that lost its race pass here and fail in
 * production. It has no store's exception to carry as the cause.
 */
final class FakeConstraintSets implements ConstraintSetRepository {

  private record Key(OrganisationId organisation, int version) {}

  private final Map<Key, ConstraintSet> stored = new HashMap<>();

  @Override
  public void save(ConstraintSet constraintSet) {
    Key key = new Key(constraintSet.organisation(), constraintSet.version());
    if (stored.containsKey(key)) {
      throw new ConstraintsRevisedConcurrentlyException(constraintSet.version(), null);
    }
    stored.put(key, constraintSet);
  }

  @Override
  public Optional<ConstraintSet> findCurrent(OrganisationId organisation) {
    return stored.values().stream()
        .filter(set -> set.organisation().equals(organisation))
        .max(Comparator.comparingInt(ConstraintSet::version));
  }

  @Override
  public Optional<ConstraintSet> findVersion(OrganisationId organisation, int version) {
    return Optional.ofNullable(stored.get(new Key(organisation, version)));
  }
}
