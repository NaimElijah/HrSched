package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
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
   * <p>Every roster it supersedes takes its pending swap requests with it: each lapses, and both
   * its parties are told, in the same transaction (R33).
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
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.SwapNotPendingException if a
   *     request it lapses was decided meanwhile, which again needs a writer that skipped the lock.
   *     Refused before anything is recorded
   */
  Roster publish(Actor actor, OrganisationId organisation, RosterId roster);

  /**
   * Publishes the replacement roster that approving a swap builds (ADR-022), and stores the
   * approval with it.
   *
   * <p>Exactly {@link #publish}, with one difference. The roster it supersedes is the one the
   * approved request names, so every other request still pending on that roster lapses (R33) and
   * this one must not. Storing the approval here, with the publication and before the lapse, is
   * what keeps it from being taken for one of them (ADR-039).
   *
   * <p>Only {@code SwapService} has a reason to call this. It records the approval and tells the
   * parties itself once this returns, after the approval is stored.
   *
   * @param actor whoever is approving; must be a Scheduler
   * @param organisation whose roster
   * @param replacement the draft carrying the swap
   * @param approved the request this publication settles, already approved
   * @return the published replacement
   * @throws IllegalArgumentException if {@code approved} is not approved, belongs to another
   *     organisation, names a roster this publication does not supersede, is not the request stored
   *     and waiting under its identity with the same roster, shift and parties, or is not carried
   *     by the replacement. Refused before anything is written
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.SwapNotPendingException if the
   *     stored request stopped being pending meanwhile, which needs a writer that skipped the
   *     publication lock
   * @see #publish for everything else this refuses
   */
  Roster publishApproving(
      Actor actor, OrganisationId organisation, RosterId replacement, SwapRequest approved);

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
