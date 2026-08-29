package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Optional;

/** Driving port: producing and publishing rosters. */
public interface PlanRostersUseCase {

  /**
   * Builds a draft roster covering a period, assigning people to every shift it can.
   *
   * @param actor whoever is generating it; must be a Scheduler or Admin
   * @param command which organisation and which period
   * @return the draft, which may leave shifts uncovered if nobody eligible is available
   */
  Roster generateDraft(Actor actor, GenerateRosterCommand command);

  /**
   * Publishes a draft, after checking it against the organisation current rules.
   *
   * @param actor whoever is publishing it; must be a Scheduler or Admin
   * @param organisation whose roster
   * @param roster which draft
   * @return the published roster
   * @throws RosterNotCompliantException if it breaks any rule. There is no override
   */
  Roster publish(Actor actor, OrganisationId organisation, RosterId roster);

  /**
   * @param actor whoever is asking
   * @param organisation whose roster
   * @param roster which one
   * @return the roster, or empty if that organisation has no such roster
   */
  Optional<Roster> findRoster(Actor actor, OrganisationId organisation, RosterId roster);
}
