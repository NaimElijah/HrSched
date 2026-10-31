package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationId;
import io.github.naimelijah.hrsched.scheduling.domain.model.GenerationsStoppedException;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.TooManyGenerationsException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.List;
import java.util.Optional;

/** Driving port: producing and publishing rosters. */
public interface PlanRostersUseCase {

  /**
   * Starts building a draft roster covering a period, assigning people to every shift it can, and
   * answers before it is built: the solve runs in the background (ADR-046).
   *
   * <p>A shift the draft covers that a published roster beside it already staffs keeps those
   * people, and nobody is put on a shift overlapping other work they hold there (R35, #130). If
   * somebody that roster puts on one of these shifts could not stay on it, the generation ends
   * refused, listing why: the published roster has to change first.
   *
   * @param actor whoever is generating it; must be a Scheduler
   * @param command which organisation and which period
   * @return the generation, pending, to be read back with {@link #findGeneration}
   * @throws TooManyGenerationsException if as many generations as may run at once already are; the
   *     generation is stored as failed
   * @throws GenerationsStoppedException if the instance has stopped taking generations because it
   *     is shutting down; the generation is stored as failed
   */
  GenerationState startGeneration(Actor actor, GenerateRosterCommand command);

  /**
   * How a generation stands: pending, or ended with a draft, refused, failed, or lost with the
   * instance that was solving it (ADR-046).
   *
   * @param actor whoever is asking; an Admin or a Scheduler of that organisation
   * @param organisation whose generation
   * @param generation which one
   * @return the generation, if that organisation asked for one with that identity
   */
  Optional<GenerationState> findGeneration(
      Actor actor, OrganisationId organisation, GenerationId generation);

  /**
   * Puts one person on one shift of a draft (requirement F2).
   *
   * <p>Checks the rules that are about the assignment itself: the person is one of the
   * organisation's own (R5), holds every qualification the shift demands (R4), and has not declared
   * that they cannot work when it runs (F6) — the same three questions generation asks — and holds
   * no other shift overlapping it (R3): on this draft, or on a published roster that stays
   * authoritative beside it (R35), neither of which generation produces. That last check takes no
   * publication lock, so publication asks it again (ADR-033). It deliberately does <em>not</em>
   * check working-time rules, which generation does as it builds whole schedules: those judge a
   * whole schedule rather than one assignment, and they are enforced where they can be enforced
   * honestly, at publication, which cannot be bypassed.
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
   * Publishes a draft, after checking it against the organisation's current rules.
   *
   * <p>Every roster it supersedes takes its pending swap requests with it: each lapses, and both
   * its parties are told, in the same transaction (R33).
   *
   * <p>It records nothing until every save and every telling has run, so a write that fails leaves
   * no audit line for a publication its rollback undid (#133).
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
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotVerifiableException if a
   *     shift it assigns, or one its people hold on a published roster that stays authoritative
   *     once it lands, cannot be resolved
   * @throws RosterNotCompliantException if it breaks any rule, listing every breach: anybody on two
   *     shifts at once, whatever the rules say - two of its own, or one beside work they hold on
   *     such a roster (R35) - anybody lacking a shift's qualifications (R4), on it through time
   *     they declared they cannot work (R18) or not of the organisation (R5), and every
   *     working-time rule broken. There is no override
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.RosterPublicationConflictException
   *     if a roster overlapping it was published at the same time by a writer that did not take
   *     turns. Publishing through this port always does (ADR-027), so this means some other writer
   *     skipped the publication lock (ADR-026, ADR-037). Refused before anything is recorded
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.SwapNotPendingException if a
   *     request it lapses was decided meanwhile, which again needs a writer that skipped the lock.
   *     Refused before anything is recorded
   */
  Roster publish(Actor actor, OrganisationId organisation, RosterId roster);

  /**
   * Publishes the replacement roster that approving a swap builds (ADR-022), stores the approval
   * with it, and tells both its parties.
   *
   * <p>Exactly {@link #publish}, with two differences, both about the approved request. It is
   * stored here: the roster this supersedes is the one the request names, so every other request
   * still pending on that roster lapses (R33) and this one must not, and storing the approval with
   * the publication, before the lapse, is what keeps it from being taken for one of them (ADR-039).
   * And its parties are told here, after the parties of every request it lapses and before the
   * publication records anything, because a telling is a write that can still fail (#133).
   *
   * <p>Only {@code SwapService} has a reason to call this. It records the approval itself once this
   * returns, when every write the approval makes has run.
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
   * Judges a draft as publishing it would, without publishing it: every rule it breaks, through the
   * same judgement publication refuses by, and every shift short of people (#205, ADR-042).
   *
   * <p>Advisory and read-only. It takes no lock, so a publication made meanwhile can change what it
   * would say; publication judges again and decides. Nothing is stored or recorded.
   *
   * @param actor whoever is asking; an Admin or a Scheduler of the organisation, who may read every
   *     roster and everybody's availability (F4, R12)
   * @param organisation whose roster
   * @param roster which draft
   * @return what publishing it would say now
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRosterException if that
   *     organisation has no such roster
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException if it
   *     is not a draft: a published roster was judged under the rules version it records (Q9)
   * @throws io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotVerifiableException if a
   *     shift it assigns, or one its people hold on a roster beside it, cannot be resolved
   */
  RosterAssessment findAssessment(Actor actor, OrganisationId organisation, RosterId roster);

  /**
   * @param actor whoever is asking; an Admin or a Scheduler of the organisation, because a roster
   *     says who works when, which is personal data about every colleague (R12)
   * @param organisation whose rosters
   * @return every roster that organisation holds, newest state included
   */
  List<Roster> findRosters(Actor actor, OrganisationId organisation);
}
