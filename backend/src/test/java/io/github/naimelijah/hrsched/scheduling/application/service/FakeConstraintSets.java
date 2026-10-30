package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.ConstraintSetRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
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
 * version already stored, as {@code constraint_set}'s primary key does: a fake that overwrote it
 * would let a revision that lost its race pass here and fail in production. It refuses with an
 * {@link IllegalStateException}, not the store's {@code DuplicateKeyException}: the port declares
 * neither, no use case tells the two apart, and the service tests stay as free of Spring as the
 * services they test.
 */
final class FakeConstraintSets implements ConstraintSetRepository {

  private record Key(OrganisationId organisation, int version) {}

  private final Map<Key, ConstraintSet> stored = new HashMap<>();

  @Override
  public void save(ConstraintSet constraintSet) {
    Key key = new Key(constraintSet.organisation(), constraintSet.version());
    if (stored.containsKey(key)) {
      throw new IllegalStateException(
          "Version %d of these rules is already stored".formatted(constraintSet.version()));
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
