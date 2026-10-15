package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Optional;

/**
 * Driving port: setting the working-time rules an organisation is bound by.
 *
 * <p>This is where Q4 is answered in practice. Nothing here mentions a country: an Admin supplies
 * thresholds, and a jurisdiction is simply a preset someone loads.
 */
public interface ConfigureConstraintsUseCase {

  /**
   * @param actor whoever is configuring them; must be an Admin of the organisation
   * @param command the rules that should apply from now on
   * @return the newly created version
   */
  ConstraintSet reviseConstraints(Actor actor, ReviseConstraintsCommand command);

  /**
   * Reading stores nothing. An organisation that has never revised its rules is bound by the empty
   * first version, which exists only as {@link ConstraintSet#initial}: the first revision is the
   * first version written, and it is version two.
   *
   * @param actor whoever is asking
   * @param organisation whose rules to read
   * @return the current rules, or the empty first version if they were never revised
   */
  ConstraintSet currentConstraints(Actor actor, OrganisationId organisation);

  /**
   * Reads back one version of the rules, as it was when it was written: what a roster published
   * under that version was judged against, however the rules have changed since (Q9). Any member
   * may, as for the current rules (F21).
   *
   * @param actor whoever is asking; must be a member of the organisation
   * @param organisation whose rules to read
   * @param version which revision, as a roster names it in {@code
   *     validatedAgainstConstraintVersion}
   * @return that version, empty if the organisation has none by that number. Version one is the
   *     empty first version, whether or not the rules have been revised since
   */
  Optional<ConstraintSet> findConstraints(Actor actor, OrganisationId organisation, int version);
}
