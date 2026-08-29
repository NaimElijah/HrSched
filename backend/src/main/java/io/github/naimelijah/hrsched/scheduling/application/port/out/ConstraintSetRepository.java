package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Optional;

/**
 * Driven port: storing an organisation working-time rules.
 *
 * <p>Only the current version is retrievable today. Storing every version is owed, because a
 * published roster records which version approved it and that record is worthless if the version
 * cannot be read back.
 */
public interface ConstraintSetRepository {

  void save(ConstraintSet constraintSet);

  Optional<ConstraintSet> findCurrent(OrganisationId organisation);
}
