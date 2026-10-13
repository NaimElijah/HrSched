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
   * that they cannot work when it runs (F6) — the same three questions generation asks — and holds
   * no other shift overlapping it (R3): on this draft, which generation never produces either, or
   * on a published roster that stays authoritative beside it (R35). That last check takes no
   * publication lock, so publication asks it again (ADR-033). It deliberately does <em>not</em>
   * check working-time rules, exactly as generation does not: those judge a whole schedule rather
   * than one assignment, and they are enforced where they can be enforced honestly, at publication,
   * which cannot be bypassed.
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
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.OverlappingAssignmentException if
   *     they already cover another shift that overlaps it, on this draft or on a published roster
   *     that stays authoritative beside it
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotVerifiableException if
   *     one of their shifts, here or on such a roster, cannot be resolved
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
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRosterException if that
   *     organisation has no such roster
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException if the
   *     roster is not a draft. Checked as soon as the roster is found, before anything else is
   *     read, superseded or recorded
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.OverlappingAssignmentException if
   *     it puts anybody on two shifts at once, whatever the rules say: two of its own, or one
   *     beside work they hold on a published roster that stays authoritative once it lands (R35).
   *     Checked before the rules
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotVerifiableException if a
   *     shift it assigns, or one its people hold on such a roster, cannot be resolved
   * @throws RosterNotCompliantException if it breaks any rule. There is no override
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.RosterPublicationConflictException
   *     if a roster overlapping it was published at the same time by a writer that did not take
   *     turns. Publishing through this port always does (ADR-027), so this means some other writer
   *     skipped the publication lock (ADR-026)
   */
  Roster publish(Actor actor, OrganisationId organisation, RosterId roster);

  /**
   * @param actor whoever is asking; an Admin or a Scheduler of the organisation (R12)
   * @param organisation whose roster
   * @param roster which one
   * @return the roster, or empty if that organisation has no such roster
   */
  Optional<Roster> findRoster(Actor actor, OrganisationId organisation, RosterId roster);

  /**
   * @param actor whoever is asking; an Admin or a Scheduler of the organisation, because a roster
   *     says who works when, which is personal data about every colleague (R12)
   * @param organisation whose rosters
   * @return every roster that organisation holds, newest state included
   */
  List<Roster> findRosters(Actor actor, OrganisationId organisation);
}
