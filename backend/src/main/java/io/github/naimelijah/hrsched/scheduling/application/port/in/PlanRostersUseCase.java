package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.Optional;

/** Driving port: producing and publishing rosters. */
public interface PlanRostersUseCase {

  /**
   * Builds a draft roster covering a period, assigning people to every shift it can.
   *
   * @param actor whoever is generating it; must be a Scheduler
   * @param command which organisation and which period
   * @return the draft, which may leave shifts uncovered if nobody eligible is available
   */
  Roster generateDraft(Actor actor, GenerateRosterCommand command);

  /**
   * Puts one person on one shift of a draft (requirement F2).
   *
   * <p>Checks the rules that are about the assignment itself: the person is one of the
   * organisation's own (R5), holds every qualification the shift demands (R4), and has not declared
   * that they cannot work when it runs (F6) — the same three questions generation asks. It
   * deliberately does <em>not</em> check working-time rules, exactly as generation does not: those
   * judge a whole schedule rather than one assignment, and they are enforced where they can be
   * enforced honestly, at publication, which cannot be bypassed.
   *
   * @param actor whoever is assigning; must be a Scheduler
   * @param command which draft, which shift, and who
   * @return the amended draft
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRosterException if that
   *     organisation has no such roster
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.UnknownShiftException if it has no
   *     such shift
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.UnknownAssigneeException if that
   *     person is not one of its rosterable employees
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.AssigneeNotQualifiedException if
   *     they do not hold every qualification the shift requires
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.AssigneeUnavailableException if
   *     they have declared they cannot work when the shift runs
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException if the
   *     roster is no longer a draft
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.DuplicateAssignmentException if
   *     they already cover that shift
   */
  Roster assign(Actor actor, AssignToShiftCommand command);

  /**
   * Takes one person off one shift of a draft (requirement F2).
   *
   * @param actor whoever is removing it; must be a Scheduler
   * @param command which draft, which shift, and who
   * @return the amended draft
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRosterException if that
   *     organisation has no such roster
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.UnknownAssignmentException if the
   *     roster does not carry that assignment
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException if the
   *     roster is no longer a draft
   */
  Roster unassign(Actor actor, RemoveAssignmentCommand command);

  /**
   * Publishes a draft, after checking it against the organisation current rules.
   *
   * @param actor whoever is publishing it; must be a Scheduler
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

  /**
   * @param actor whoever is asking; any member of the organisation
   * @param organisation whose rosters
   * @return every roster that organisation holds, newest state included
   */
  List<Roster> findRosters(Actor actor, OrganisationId organisation);
}
