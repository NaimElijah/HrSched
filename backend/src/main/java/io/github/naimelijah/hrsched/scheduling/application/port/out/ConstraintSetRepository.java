package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintsRevisedConcurrentlyException;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Optional;

/**
 * Driven port: storing an organisation's working-time rules, every version of them.
 *
 * <p>Every version is kept, because a published roster records the version that approved it, and
 * that record is worth something only if the version can be read back (Q9).
 */
public interface ConstraintSetRepository {

  /**
   * @param constraintSet a new version of its organisation's rules
   * @throws ConstraintsRevisedConcurrentlyException if that version is already stored: another
   *     revision computed the same next version and was stored first
   */
  void save(ConstraintSet constraintSet);

  /**
   * @param organisation whose rules to read
   * @return the highest version stored, empty if the organisation has never revised its rules
   * @throws IllegalStateException if that version is stored as no set the domain allows, such as
   *     two rules of one kind (R24): a fault in stored data, not the caller's mistake (#96)
   */
  Optional<ConstraintSet> findCurrent(OrganisationId organisation);

  /**
   * Reads back one version, with the rules it had when it was written.
   *
   * <p>Only what was stored is found. The empty first version every organisation starts with is
   * never stored (see {@link ConstraintSet#initial}), so it is not found here either.
   *
   * @param organisation whose rules to read; another organisation's version is never found
   * @param version which revision
   * @return that version, empty if this organisation never stored it
   * @throws IllegalStateException if it is stored as no set the domain allows, as {@link
   *     #findCurrent} does
   */
  Optional<ConstraintSet> findVersion(OrganisationId organisation, int version);
}
