package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.in.AssignToShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ConfigureConstraintsUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.DefineShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.GenerateRosterCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ManageShiftsUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.PlanRostersUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.RemoveAssignmentCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ReviseConstraintsCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.RosteredShift;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ViewOwnScheduleUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.scheduling.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ConstraintSetRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.SchedulingChange;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ShiftRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.WorkforceDirectory;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintViolation;
import io.github.naimelijah.hrsched.scheduling.domain.model.NoEmployeeRecordException;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotVerifiableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftOutsideRosterPeriodException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRequiredQualificationException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRosterException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownShiftException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

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
    implements ManageShiftsUseCase,
        ConfigureConstraintsUseCase,
        PlanRostersUseCase,
        ViewOwnScheduleUseCase {

  /**
   * Building rosters belongs to the Scheduler alone. An Admin manages people, qualifications and
   * the constraint set — {@link Role#ADMIN} says so, and so does requirements F1/F3/F11/F12.
   */
  private static final Set<Role> MAY_SCHEDULE = Set.of(Role.SCHEDULER);

  private static final Set<Role> MAY_CONFIGURE = Set.of(Role.ADMIN);

  /**
   * Reading a whole roster is restricted for the same reason listing people is: it says who works
   * when, which is personal data about every colleague.
   *
   * <p>An employee seeing only their own work is the narrower capability beside this one, answered
   * by {@link ViewOwnScheduleUseCase} rather than by widening this set. That is the answer to Q18:
   * "their own" means the assignments naming them, from published rosters only.
   */
  private static final Set<Role> MAY_SEE_ROSTERS = Set.of(Role.ADMIN, Role.SCHEDULER);

  private final ShiftRepository shifts;
  private final RosterRepository rosters;
  private final ConstraintSetRepository constraintSets;
  private final WorkforceDirectory workforce;
  private final RosterGenerator generator;
  private final AuditTrail audit;

  public SchedulingService(
      ShiftRepository shifts,
      RosterRepository rosters,
      ConstraintSetRepository constraintSets,
      WorkforceDirectory workforce,
      RosterGenerator generator,
      AuditTrail audit) {
    this.shifts = Objects.requireNonNull(shifts, "shifts must not be null");
    this.rosters = Objects.requireNonNull(rosters, "rosters must not be null");
    this.constraintSets = Objects.requireNonNull(constraintSets, "constraintSets must not be null");
    this.workforce = Objects.requireNonNull(workforce, "workforce must not be null");
    this.generator = Objects.requireNonNull(generator, "generator must not be null");
    this.audit = Objects.requireNonNull(audit, "audit must not be null");
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
    recorded(actor, command.organisation(), SchedulingChange.SHIFT_DEFINED, shift.id().value());
    return shift.id();
  }

  @Override
  public List<Shift> findShifts(Actor actor, OrganisationId organisation, TimeRange within) {
    actor.requireAccessTo(organisation);
    return shifts.findWithin(organisation, within);
  }

  @Override
  public Optional<Shift> findShift(Actor actor, OrganisationId organisation, ShiftId shift) {
    actor.requireAccessTo(organisation);
    return shifts.findById(organisation, shift);
  }

  @Override
  public ConstraintSet reviseConstraints(Actor actor, ReviseConstraintsCommand command) {
    authorise(actor, command.organisation(), MAY_CONFIGURE);
    ConstraintSet revised =
        currentOrInitial(command.organisation()).revisedTo(command.constraints());
    constraintSets.save(revised);
    recorded(
        actor,
        command.organisation(),
        SchedulingChange.CONSTRAINTS_REVISED,
        command.organisation().value());
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
    recorded(actor, command.organisation(), SchedulingChange.ROSTER_GENERATED, draft.id().value());
    return draft;
  }

  @Override
  public Roster assign(Actor actor, AssignToShiftCommand command) {
    authorise(actor, command.organisation(), MAY_SCHEDULE);
    Roster draft = draftOrUnknown(command.organisation(), command.roster());
    Shift shift =
        shifts
            .findById(command.organisation(), command.shift())
            .orElseThrow(() -> new UnknownShiftException(command.shift()));
    // Generation can only ever assign shifts it found inside the roster's period; assigning by hand
    // has to be held to the same thing, or supersession stops meaning anything. See R11.
    if (!shift.period().overlaps(draft.period())) {
      throw new ShiftOutsideRosterPeriodException(draft.id(), shift.id());
    }
    AssignmentEligibility.require(
        workforce, actor, command.organisation(), command.employee(), shift);

    Roster amended = draft.with(new Assignment(command.shift(), command.employee()));
    // R3. Publication would refuse this roster too, but only here can the Scheduler be told which
    // assignment did it, while they are making it.
    amended.requireNoOverlappingWorkFor(
        command.employee(),
        periodsOf(
            command.organisation(),
            amended,
            amended.assignments().stream()
                .filter(assignment -> assignment.employee().equals(command.employee()))
                .toList()));
    rosters.save(amended);
    recorded(
        actor, command.organisation(), SchedulingChange.ASSIGNMENT_ADDED, amended.id().value());
    return amended;
  }

  @Override
  public Roster unassign(Actor actor, RemoveAssignmentCommand command) {
    authorise(actor, command.organisation(), MAY_SCHEDULE);
    Roster draft = draftOrUnknown(command.organisation(), command.roster());

    Roster amended = draft.without(new Assignment(command.shift(), command.employee()));
    rosters.save(amended);
    recorded(
        actor, command.organisation(), SchedulingChange.ASSIGNMENT_REMOVED, amended.id().value());
    return amended;
  }

  private Roster draftOrUnknown(OrganisationId organisation, RosterId rosterId) {
    return rosters
        .findById(organisation, rosterId)
        .orElseThrow(() -> new UnknownRosterException(rosterId));
  }

  @Override
  public Roster publish(Actor actor, OrganisationId organisation, RosterId rosterId) {
    authorise(actor, organisation, MAY_SCHEDULE);
    // Before anything is read. What this supersedes is decided from what it reads, and a
    // publication still in flight would otherwise be invisible to it (ADR-027).
    rosters.lockPublicationsOf(organisation);
    Roster draft =
        rosters
            .findById(organisation, rosterId)
            .orElseThrow(() -> new UnknownRosterException(rosterId));
    ConstraintSet constraints = currentOrInitial(organisation);

    List<ConstraintViolation> violations = violations(organisation, draft, constraints);
    if (!violations.isEmpty()) {
      throw new RosterNotCompliantException(violations);
    }

    supersedeOverlapping(actor, organisation, draft);
    Roster published = draft.publishedUnder(constraints.version());
    rosters.save(published);
    recorded(actor, organisation, SchedulingChange.ROSTER_PUBLISHED, published.id().value());
    return published;
  }

  @Override
  public List<RosteredShift> findOwnSchedule(
      Actor actor, OrganisationId organisation, TimeRange within) {
    actor.requireAccessTo(organisation);
    EmployeeId me = personalRecordOf(actor);
    return rosters.findByOrganisation(organisation).stream()
        .filter(roster -> roster.status() == RosterStatus.PUBLISHED)
        .flatMap(roster -> ownShiftsIn(organisation, roster, me))
        .filter(rostered -> rostered.period().overlaps(within))
        .sorted(Comparator.comparing(rostered -> rostered.period().start()))
        .toList();
  }

  /**
   * The link ADR-012 exists for. A user is not an employee, so this reads the personnel record the
   * actor carries rather than comparing a {@code UserId} with an {@code EmployeeId} — which are
   * different identities on purpose and would never match.
   */
  private static EmployeeId personalRecordOf(Actor actor) {
    if (actor instanceof TenantActor tenant) {
      return tenant.employee().orElseThrow(() -> new NoEmployeeRecordException(actor.id()));
    }
    // An Operator belongs to no organisation and is nobody's colleague, so there is no personnel
    // record to answer for. Refusing beats returning nothing: an empty schedule would read as
    // "you are rostered for nothing this week", which is a different and reassuring falsehood.
    throw new NoEmployeeRecordException(actor.id());
  }

  /**
   * Resolves this person's assignments on one published roster into work with a time attached.
   *
   * <p>A shift the repository cannot return fails rather than being skipped, for the same reason
   * publication refuses: the roster said somebody works then, and quietly dropping it would show
   * them a week with a hole in it that nobody could account for.
   */
  private Stream<RosteredShift> ownShiftsIn(
      OrganisationId organisation, Roster roster, EmployeeId me) {
    return roster.assignments().stream()
        .filter(assignment -> assignment.employee().equals(me))
        .map(
            assignment -> {
              Shift shift =
                  shifts
                      .findById(organisation, assignment.shift())
                      .orElseThrow(
                          () -> new RosterNotVerifiableException(roster.id(), assignment.shift()));
              return new RosteredShift(shift.id(), shift.period(), roster.id());
            });
  }

  @Override
  public Optional<Roster> findRoster(Actor actor, OrganisationId organisation, RosterId rosterId) {
    authorise(actor, organisation, MAY_SEE_ROSTERS);
    return rosters.findById(organisation, rosterId);
  }

  @Override
  public List<Roster> findRosters(Actor actor, OrganisationId organisation) {
    authorise(actor, organisation, MAY_SEE_ROSTERS);
    return rosters.findByOrganisation(organisation);
  }

  /**
   * Evaluates every employee schedule in the roster against the organisation rules.
   *
   * @return every rule broken, empty if the roster may be published
   */
  private List<ConstraintViolation> violations(
      OrganisationId organisation, Roster roster, ConstraintSet constraints) {
    return roster.schedulesFor(periodsOf(organisation, roster, roster.assignments())).stream()
        .map(constraints::violationsFor)
        .flatMap(List::stream)
        .toList();
  }

  /**
   * When the shifts of these assignments run, which the roster itself does not know.
   *
   * <p>A shift the repository cannot return refuses rather than being left out: whatever is judged
   * against these periods would otherwise be judged against less work than the roster carries.
   *
   * @throws RosterNotVerifiableException if any of those shifts cannot be resolved
   */
  private Map<ShiftId, TimeRange> periodsOf(
      OrganisationId organisation, Roster roster, List<Assignment> assignments) {
    Map<ShiftId, TimeRange> periods = new LinkedHashMap<>();
    for (Assignment assignment : assignments) {
      Shift shift =
          shifts
              .findById(organisation, assignment.shift())
              .orElseThrow(() -> new RosterNotVerifiableException(roster.id(), assignment.shift()));
      periods.put(shift.id(), shift.period());
    }
    return periods;
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
  private void supersedeOverlapping(Actor actor, OrganisationId organisation, Roster replacement) {
    rosters.findByOrganisation(organisation).stream()
        .filter(existing -> existing.status() == RosterStatus.PUBLISHED)
        .filter(existing -> !existing.id().equals(replacement.id()))
        .filter(existing -> existing.period().overlaps(replacement.period()))
        .forEach(
            existing -> {
              rosters.save(existing.superseded());
              // Recorded per roster, not once for the publication that caused it. Nobody asked for
              // these demotions, so without an entry each a roster appears to demote itself.
              recorded(
                  actor, organisation, SchedulingChange.ROSTER_SUPERSEDED, existing.id().value());
            });
  }

  /**
   * Records a change that has already been saved.
   *
   * <p>Called after the repository, never before: an entry for something that then failed to store
   * is worse than no entry, because it is a record of something that did not happen.
   */
  private void recorded(
      Actor actor, OrganisationId organisation, SchedulingChange change, UUID subject) {
    audit.record(new AuditedChange(organisation, actor.id(), change, subject));
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
    actor.requireAccessTo(organisation).requireRoleIn(permitted);
  }
}
