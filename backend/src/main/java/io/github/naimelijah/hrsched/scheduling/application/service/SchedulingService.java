package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.in.ConfigureConstraintsUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.DefineShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.GenerateRosterCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ManageShiftsUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.PlanRostersUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ReviseConstraintsCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ConstraintSetRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ShiftRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.WorkforceDirectory;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintViolation;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotVerifiableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRequiredQualificationException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRosterException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates the scheduling use cases.
 *
 * <p>Holds no rules of its own. It authorises, gathers what the domain needs from ports, lets the
 * domain judge, and stores the outcome. Carries no Spring annotation, so it can be constructed with
 * {@code new} in a test.
 *
 * <p>Constraint checking lives here rather than inside {@link Roster} because it needs data the
 * aggregate cannot reach: when each shift runs, and who is available. The judgement itself is still
 * the domain's — this class only assembles the evidence and calls {@link ConstraintSet}.
 */
public class SchedulingService
    implements ManageShiftsUseCase, ConfigureConstraintsUseCase, PlanRostersUseCase {

  /**
   * Building rosters belongs to the Scheduler alone. An Admin manages people, qualifications and
   * the constraint set — {@link Role#ADMIN} says so, and so does requirements F1/F3/F11/F12.
   */
  private static final Set<Role> MAY_SCHEDULE = Set.of(Role.SCHEDULER);

  private static final Set<Role> MAY_CONFIGURE = Set.of(Role.ADMIN);

  private final ShiftRepository shifts;
  private final RosterRepository rosters;
  private final ConstraintSetRepository constraintSets;
  private final WorkforceDirectory workforce;
  private final RosterGenerator generator;

  public SchedulingService(
      ShiftRepository shifts,
      RosterRepository rosters,
      ConstraintSetRepository constraintSets,
      WorkforceDirectory workforce,
      RosterGenerator generator) {
    this.shifts = Objects.requireNonNull(shifts, "shifts must not be null");
    this.rosters = Objects.requireNonNull(rosters, "rosters must not be null");
    this.constraintSets = Objects.requireNonNull(constraintSets, "constraintSets must not be null");
    this.workforce = Objects.requireNonNull(workforce, "workforce must not be null");
    this.generator = Objects.requireNonNull(generator, "generator must not be null");
  }

  @Override
  public ShiftId defineShift(Actor actor, DefineShiftCommand command) {
    authorise(actor, command.organisation(), MAY_SCHEDULE);
    requireDefinedQualifications(actor, command.organisation(), command.requiredQualifications());
    Shift shift =
        new Shift(
            ShiftId.newId(),
            command.organisation(),
            command.period(),
            command.requiredHeadcount(),
            command.requiredQualifications());
    shifts.save(shift);
    return shift.id();
  }

  @Override
  public List<Shift> findShifts(Actor actor, OrganisationId organisation, TimeRange within) {
    actor.requireAccessTo(organisation);
    return shifts.findWithin(organisation, within);
  }

  @Override
  public ConstraintSet reviseConstraints(Actor actor, ReviseConstraintsCommand command) {
    authorise(actor, command.organisation(), MAY_CONFIGURE);
    ConstraintSet revised =
        currentOrInitial(command.organisation()).revisedTo(command.constraints());
    constraintSets.save(revised);
    return revised;
  }

  @Override
  public ConstraintSet currentConstraints(Actor actor, OrganisationId organisation) {
    actor.requireAccessTo(organisation);
    return currentOrInitial(organisation);
  }

  @Override
  public Roster generateDraft(Actor actor, GenerateRosterCommand command) {
    authorise(actor, command.organisation(), MAY_SCHEDULE);
    List<Shift> toCover = shifts.findWithin(command.organisation(), command.period());
    List<RosterCandidate> candidates = workforce.candidatesFor(actor, command.organisation());
    ConstraintSet constraints = currentOrInitial(command.organisation());

    List<Assignment> assignments = generator.generate(toCover, candidates, constraints);

    Roster draft =
        new Roster(RosterId.newId(), command.organisation(), command.period())
            .withAssignments(assignments);
    rosters.save(draft);
    return draft;
  }

  @Override
  public Roster publish(Actor actor, OrganisationId organisation, RosterId rosterId) {
    authorise(actor, organisation, MAY_SCHEDULE);
    Roster draft =
        rosters
            .findById(organisation, rosterId)
            .orElseThrow(() -> new UnknownRosterException(rosterId));
    ConstraintSet constraints = currentOrInitial(organisation);

    List<ConstraintViolation> violations = violations(organisation, draft, constraints);
    if (!violations.isEmpty()) {
      throw new RosterNotCompliantException(violations);
    }

    supersedeOverlapping(organisation, draft);
    Roster published = draft.publishedUnder(constraints.version());
    rosters.save(published);
    return published;
  }

  @Override
  public Optional<Roster> findRoster(Actor actor, OrganisationId organisation, RosterId rosterId) {
    actor.requireAccessTo(organisation);
    return rosters.findById(organisation, rosterId);
  }

  @Override
  public List<Roster> findRosters(Actor actor, OrganisationId organisation) {
    actor.requireAccessTo(organisation);
    return rosters.findByOrganisation(organisation);
  }

  /**
   * Evaluates every employee schedule in the roster against the organisation rules.
   *
   * @return every rule broken, empty if the roster may be published
   */
  private List<ConstraintViolation> violations(
      OrganisationId organisation, Roster roster, ConstraintSet constraints) {
    Map<ShiftId, TimeRange> periods = new LinkedHashMap<>();
    for (Assignment assignment : roster.assignments()) {
      Shift shift =
          shifts
              .findById(organisation, assignment.shift())
              .orElseThrow(() -> new RosterNotVerifiableException(roster.id(), assignment.shift()));
      periods.put(shift.id(), shift.period());
    }
    return roster.schedulesFor(periods).stream()
        .map(constraints::violationsFor)
        .flatMap(List::stream)
        .toList();
  }

  /**
   * Marks every published roster this one replaces as superseded, so that exactly one roster is
   * authoritative for any given moment.
   *
   * <p>Overlap, not equality, decides what is replaced: a roster republished for a shorter window
   * during the week still governs the days it covers, and leaving the old one PUBLISHED would give
   * those days two authoritative answers. This is the mechanism that removes the need for an
   * override — see ADR-005 and requirement R6.
   */
  private void supersedeOverlapping(OrganisationId organisation, Roster replacement) {
    rosters.findByOrganisation(organisation).stream()
        .filter(existing -> existing.status() == RosterStatus.PUBLISHED)
        .filter(existing -> !existing.id().equals(replacement.id()))
        .filter(existing -> existing.period().overlaps(replacement.period()))
        .forEach(existing -> rosters.save(existing.superseded()));
  }

  /** Refuses a shift demanding a qualification the organisation has never defined. */
  private void requireDefinedQualifications(
      Actor actor, OrganisationId organisation, Set<QualificationId> required) {
    if (required.isEmpty()) {
      return;
    }
    Set<QualificationId> defined = workforce.definedQualifications(actor, organisation);
    Set<QualificationId> unknown = new LinkedHashSet<>(required);
    unknown.removeAll(defined);
    if (!unknown.isEmpty()) {
      throw new UnknownRequiredQualificationException(unknown);
    }
  }

  private ConstraintSet currentOrInitial(OrganisationId organisation) {
    return constraintSets
        .findCurrent(organisation)
        .orElseGet(() -> ConstraintSet.initial(organisation));
  }

  private void authorise(Actor actor, OrganisationId organisation, Set<Role> permitted) {
    actor.requireAccessTo(organisation);
    if (actor instanceof TenantActor tenant) {
      tenant.requireRoleIn(permitted);
    }
  }
}
