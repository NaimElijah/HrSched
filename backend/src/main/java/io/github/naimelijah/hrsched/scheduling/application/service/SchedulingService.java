package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.in.AssignToShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ConfigureConstraintsUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ConfigureObjectivesUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.DefineShiftCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.GenerateRosterCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ManageShiftsUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.PlanRostersUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.RemoveAssignmentCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ReviseConstraintsCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ReviseObjectivesCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.RosterAssessment;
import io.github.naimelijah.hrsched.scheduling.application.port.in.RosteredShift;
import io.github.naimelijah.hrsched.scheduling.application.port.in.ViewOwnScheduleUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.scheduling.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ConstraintSetRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.GenerationRequest;
import io.github.naimelijah.hrsched.scheduling.application.port.out.Notifier;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ObjectiveWeightsRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.SchedulingChange;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ShiftRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.SwapRequestRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.UnitsOfWork;
import io.github.naimelijah.hrsched.scheduling.application.port.out.WorkforceDirectory;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintViolation;
import io.github.naimelijah.hrsched.scheduling.domain.model.HeldWork;
import io.github.naimelijah.hrsched.scheduling.domain.model.NoEmployeeRecordException;
import io.github.naimelijah.hrsched.scheduling.domain.model.ObjectiveWeights;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterMeasures;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotCompliantException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotVerifiableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterScore;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftOutsideRosterPeriodException;
import io.github.naimelijah.hrsched.scheduling.domain.model.StatedPreferences;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRequiredQualificationException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRosterException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownShiftException;
import io.github.naimelijah.hrsched.scheduling.domain.model.WorkingPeriod;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
        ConfigureObjectivesUseCase,
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
  private final ObjectiveWeightsRepository objectiveWeights;
  private final SwapRequestRepository swaps;
  private final WorkforceDirectory workforce;
  private final RosterGenerator generator;
  private final UnitsOfWork units;
  private final Notifier notifier;
  private final AuditTrail audit;
  private final Clock clock;

  /**
   * @param objectiveWeights how each organisation weights the objectives a draft is scored on
   *     (ADR-010)
   * @param units the units of work generation marks out for itself, so that its solve holds no
   *     connection (ADR-045)
   * @param swaps the requests a publication lapses when it supersedes the roster they name (R33)
   * @param notifier how the parties to a lapsed request are told
   * @param clock what a publication reads the time from, so that when it happened is an input a
   *     test supplies rather than an ambient fact
   */
  public SchedulingService(
      ShiftRepository shifts,
      RosterRepository rosters,
      ConstraintSetRepository constraintSets,
      ObjectiveWeightsRepository objectiveWeights,
      SwapRequestRepository swaps,
      WorkforceDirectory workforce,
      RosterGenerator generator,
      UnitsOfWork units,
      Notifier notifier,
      AuditTrail audit,
      Clock clock) {
    this.shifts = Objects.requireNonNull(shifts, "shifts must not be null");
    this.rosters = Objects.requireNonNull(rosters, "rosters must not be null");
    this.constraintSets = Objects.requireNonNull(constraintSets, "constraintSets must not be null");
    this.objectiveWeights =
        Objects.requireNonNull(objectiveWeights, "objectiveWeights must not be null");
    this.swaps = Objects.requireNonNull(swaps, "swaps must not be null");
    this.workforce = Objects.requireNonNull(workforce, "workforce must not be null");
    this.generator = Objects.requireNonNull(generator, "generator must not be null");
    this.units = Objects.requireNonNull(units, "units must not be null");
    this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
    this.audit = Objects.requireNonNull(audit, "audit must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
            command.zone(),
            command.requiredHeadcount(),
            command.requiredQualifications(),
            command.unpopular());
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
  public Optional<ConstraintSet> findConstraints(
      Actor actor, OrganisationId organisation, int version) {
    actor.requireAccessTo(organisation);
    // The empty first version is never stored, because reading the rules stores nothing and the
    // first revision writes version two. A roster published before anybody revised anything still
    // names it, so it is answered from the domain rather than reported missing.
    return constraintSets
        .findVersion(organisation, version)
        .or(
            () ->
                Optional.of(ConstraintSet.initial(organisation))
                    .filter(initial -> initial.version() == version));
  }

  /**
   * F35. The weights are checked as the domain builds them, after the actor is authorised and
   * before anything is stored, so a refusal leaves neither a row nor an audit line behind.
   */
  @Override
  public ObjectiveWeights reviseObjectives(Actor actor, ReviseObjectivesCommand command) {
    authorise(actor, command.organisation(), MAY_CONFIGURE);
    ObjectiveWeights revised = new ObjectiveWeights(command.organisation(), command.weights());
    objectiveWeights.save(revised);
    recorded(
        actor,
        command.organisation(),
        SchedulingChange.OBJECTIVES_REVISED,
        command.organisation().value());
    return revised;
  }

  @Override
  public ObjectiveWeights findObjectives(Actor actor, OrganisationId organisation) {
    actor.requireAccessTo(organisation);
    return weightsOf(organisation);
  }

  /**
   * Not one transaction, unlike every other use case here: it reads in one unit of work, generates
   * outside any, and stores the draft in another, so that a solve running for seconds holds no
   * connection (ADR-045). A draft stored from reads that changed during the solve is a draft like
   * any other: publication judges it again.
   */
  @Override
  public Roster generateDraft(Actor actor, GenerateRosterCommand command) {
    authorise(actor, command.organisation(), MAY_SCHEDULE);
    GenerationRequest request = units.reading(() -> generationRequestFor(actor, command));

    Roster draft = generated(request, generator.generate(request));
    units.writing(
        () -> {
          rosters.save(draft);
          return draft;
        });
    recorded(actor, command.organisation(), SchedulingChange.ROSTER_GENERATED, draft.id().value());
    return draft;
  }

  /**
   * Everything generation decides from, with the work people already hold on the rosters that stay
   * authoritative beside the new draft (R35), the organisation's weights, and the published rosters
   * the draft would replace, which its disruption is counted against (ADR-010).
   *
   * <p>A shift this draft covers that one of those rosters already staffs keeps the same people:
   * they start on the draft, count toward its headcount, and the generator fills only the places
   * left (#130, decided on 2026-10-22). Any other work held there is time its holder is busy.
   *
   * @throws RosterNotCompliantException if the work carried over cannot stay: somebody on it is no
   *     longer eligible for it, or it breaks a rule by itself. Refused before anything is
   *     generated, because leaving them off would have the published roster and the new draft
   *     disagree about who works that shift; it is the published one that has to change
   * @throws RosterNotVerifiableException if a shift held on one of those rosters cannot be resolved
   */
  private GenerationRequest generationRequestFor(Actor actor, GenerateRosterCommand command) {
    OrganisationId organisation = command.organisation();
    Roster empty = new Roster(RosterId.newId(), organisation, command.period());
    Map<ShiftId, Shift> toCover = new LinkedHashMap<>();
    shifts
        .findWithin(organisation, command.period())
        .forEach(shift -> toCover.put(shift.id(), shift));
    List<RosterCandidate> candidates = workforce.candidatesFor(actor, organisation);
    ConstraintSet constraints = currentOrInitial(organisation);
    List<Roster> existing = rosters.findByOrganisation(organisation);
    List<Roster> authoritative =
        existing.stream().filter(roster -> roster.staysAuthoritativeBeside(empty)).toList();

    Set<EmployeeId> concerned = new LinkedHashSet<>();
    candidates.forEach(candidate -> concerned.add(candidate.id()));
    authoritative.stream()
        .flatMap(roster -> roster.assignments().stream())
        .filter(assignment -> toCover.containsKey(assignment.shift()))
        .forEach(assignment -> concerned.add(assignment.employee()));
    List<HeldWork> held = heldElsewhere(organisation, authoritative, concerned);
    Roster seeded =
        empty.withAssignments(
            held.stream()
                .filter(work -> toCover.containsKey(work.shift()))
                .map(work -> new Assignment(work.shift(), work.employee()))
                .distinct()
                .toList());

    List<ConstraintViolation> violations = judged(seeded, constraints, toCover, held, candidates);
    if (!violations.isEmpty()) {
      throw new RosterNotCompliantException(violations);
    }
    return new GenerationRequest(
        seeded,
        List.copyOf(toCover.values()),
        candidates,
        constraints,
        held,
        weightsOf(organisation),
        existing.stream().filter(roster -> roster.isSupersededBy(seeded)).toList());
  }

  /**
   * The draft a generator's answer makes, once that answer is shown to keep every promise of {@link
   * RosterGenerator#generate}.
   *
   * <p>Checked here rather than trusted, so it holds whichever generator is wired in. A generator
   * is our own code, so an answer breaking a promise is a defect in it: refused loudly before
   * anything is stored, rather than saved as a draft that publication would refuse only later.
   *
   * @param added what the generator answered
   * @return the request's draft with {@code added} on it
   * @throws IllegalStateException naming, by identity only, every promise the answer breaks
   */
  private static Roster generated(GenerationRequest request, List<Assignment> added) {
    Map<ShiftId, Shift> offered = new LinkedHashMap<>();
    request.shifts().forEach(shift -> offered.put(shift.id(), shift));
    Set<EmployeeId> candidates =
        request.candidates().stream().map(RosterCandidate::id).collect(Collectors.toSet());

    List<String> broken = new ArrayList<>();
    Set<Assignment> onDraft = new LinkedHashSet<>(request.draft().assignments());
    for (Assignment assignment : added) {
      if (!offered.containsKey(assignment.shift())) {
        broken.add("shift %s was not offered".formatted(assignment.shift().value()));
      }
      if (!candidates.contains(assignment.employee())) {
        broken.add("employee %s is not a candidate".formatted(assignment.employee().value()));
      }
      if (!onDraft.add(assignment)) {
        broken.add(
            "employee %s is on shift %s twice"
                .formatted(assignment.employee().value(), assignment.shift().value()));
      }
    }
    if (!broken.isEmpty()) {
      throw new IllegalStateException("The generator broke its promises: " + broken);
    }

    Roster draft = request.draft().withAssignments(List.copyOf(onDraft));
    Map<ShiftId, Integer> carried = new HashMap<>();
    request
        .draft()
        .assignments()
        .forEach(assignment -> carried.merge(assignment.shift(), 1, Integer::sum));
    Map<ShiftId, Integer> staffed = new LinkedHashMap<>();
    draft.assignments().forEach(assignment -> staffed.merge(assignment.shift(), 1, Integer::sum));
    staffed.forEach(
        (shift, count) -> {
          int allowed =
              Math.max(offered.get(shift).requiredHeadcount(), carried.getOrDefault(shift, 0));
          if (count > allowed) {
            broken.add("shift %s is staffed above %d".formatted(shift.value(), allowed));
          }
        });
    judged(draft, request.constraints(), offered, request.heldElsewhere(), request.candidates())
        .forEach(violation -> broken.add(violation.toString()));
    if (!broken.isEmpty()) {
      throw new IllegalStateException("The generator broke its promises: " + broken);
    }
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
    // R3 and R35. Publication would refuse this roster too, but only here can the Scheduler be told
    // which assignment did it, while they are making it. This reads without the publication lock,
    // so it is early word rather than the guarantee: publication checks again under it (ADR-033).
    amended.requireNoOverlappingWorkFor(
        command.employee(),
        periodsOf(
            command.organisation(),
            amended,
            amended.assignments().stream()
                .filter(assignment -> assignment.employee().equals(command.employee()))
                .toList()),
        heldElsewhere(
            command.organisation(),
            rosters.findByOrganisation(command.organisation()).stream()
                .filter(existing -> existing.staysAuthoritativeBeside(amended))
                .toList(),
            Set.of(command.employee())));
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

  /**
   * Held from this read until the edit commits, because the edit stores the roster whole: a second
   * edit of the same draft waits, then reads this one's change rather than storing over it (#33,
   * ADR-041).
   */
  private Roster draftOrUnknown(OrganisationId organisation, RosterId rosterId) {
    return rosters
        .findForUpdate(organisation, rosterId)
        .orElseThrow(() -> new UnknownRosterException(rosterId));
  }

  @Override
  public Roster publish(Actor actor, OrganisationId organisation, RosterId rosterId) {
    return publish(actor, organisation, rosterId, Optional.empty());
  }

  @Override
  public Roster publishApproving(
      Actor actor, OrganisationId organisation, RosterId replacement, SwapRequest approved) {
    Objects.requireNonNull(approved, "approved must not be null");
    return publish(actor, organisation, replacement, Optional.of(approved));
  }

  /**
   * @param approving the swap request this publication settles, if a swap approval is what builds
   *     it. It is stored as approved rather than lapsed with the rest (ADR-039)
   */
  private Roster publish(
      Actor actor,
      OrganisationId organisation,
      RosterId rosterId,
      Optional<SwapRequest> approving) {
    authorise(actor, organisation, MAY_SCHEDULE);
    // Before anything is read. What this supersedes is decided from what it reads, and a
    // publication still in flight would otherwise be invisible to it (ADR-027).
    rosters.lockPublicationsOf(organisation);
    // Held, as an edit holds it: what is published is what was judged, so an edit landing between
    // this read and the save below would be stored over (ADR-041).
    Roster draft =
        rosters
            .findForUpdate(organisation, rosterId)
            .orElseThrow(() -> new UnknownRosterException(rosterId));
    // Before anything else is read or written. Supersession saves and records, and an audit line
    // outlives the rollback of a refusal that came after it (#133).
    draft.requireDraft();
    ConstraintSet constraints = currentOrInitial(organisation);
    // One read decides both what this publication replaces and what it is judged beside, so the two
    // can never disagree about a roster (R11, R35).
    List<Roster> existing = rosters.findByOrganisation(organisation);

    List<ConstraintViolation> violations =
        violationsOf(
            organisation,
            draft,
            constraints,
            existing,
            workforce.candidatesFor(actor, organisation));
    if (!violations.isEmpty()) {
      throw new RosterNotCompliantException(violations);
    }

    List<Roster> replaced =
        existing.stream().filter(roster -> roster.isSupersededBy(draft)).toList();
    // Before anything is written, for the reason requireDraft is: a refusal after the supersession
    // would leave audit lines for a publication that never happened.
    approving.ifPresent(request -> requireSettledBy(request, organisation, replaced, draft));

    // Read under the lock, from the history the lock froze, and once: the rosters this supersedes
    // stop governing at the very moment this one starts to (R37, ADR-037).
    Instant at = Roster.nextMomentOfRecord(clock.instant(), existing);
    // The swap writes come before anything is recorded. A refusal part-way through takes the
    // writes back, but never an audit line (ADR-023). The approval is stored first, so the lapse
    // that follows never takes it for a request still pending.
    approving.ifPresent(swaps::save);
    List<SwapRequest> lapsed = lapsePendingSwapsOn(organisation, replaced);
    supersede(actor, organisation, replaced, at);
    Roster published = draft.publishedUnder(constraints.version(), at);
    rosters.save(published);
    recorded(actor, organisation, SchedulingChange.ROSTER_PUBLISHED, published.id().value());
    tellOfLapses(actor, organisation, lapsed);
    return published;
  }

  /**
   * An approval names the request it settles so that the lapse leaves it alone. Anything else in
   * that place is a caller's mistake, never a state a user can reach, so it is refused as one: a
   * request nobody raised, one that differs from what was asked, or one the roster being published
   * does not carry. Read under the lock, so what is stored cannot change before it is written.
   */
  private void requireSettledBy(
      SwapRequest approved, OrganisationId organisation, List<Roster> replaced, Roster draft) {
    if (approved.status() != SwapRequestStatus.APPROVED) {
      throw new IllegalArgumentException(
          "Only an approved swap request can be published as approved, not one that is "
              + approved.status());
    }
    if (!approved.organisation().equals(organisation)) {
      throw new IllegalArgumentException(
          "Swap request %s belongs to another organisation".formatted(approved.id().value()));
    }
    if (replaced.stream().noneMatch(roster -> roster.id().equals(approved.roster()))) {
      throw new IllegalArgumentException(
          "Swap request %s names a roster this publication does not supersede"
              .formatted(approved.id().value()));
    }
    boolean asked =
        swaps
            .findById(organisation, approved.id())
            .filter(stored -> stored.status() == SwapRequestStatus.REQUESTED)
            .filter(stored -> isTheSameAsk(stored, approved))
            .isPresent();
    if (!asked) {
      throw new IllegalArgumentException(
          "Swap request %s is not one waiting on a Scheduler as it was asked"
              .formatted(approved.id().value()));
    }
    if (!draft.assignments().contains(approved.taken())
        || draft.assignments().contains(approved.given())) {
      throw new IllegalArgumentException(
          "The roster being published does not carry swap request %s"
              .formatted(approved.id().value()));
    }
  }

  private static boolean isTheSameAsk(SwapRequest stored, SwapRequest approved) {
    return stored.roster().equals(approved.roster())
        && stored.shift().equals(approved.shift())
        && stored.requester().equals(approved.requester())
        && stored.counterparty().equals(approved.counterparty())
        && stored.requestedAt().equals(approved.requestedAt());
  }

  /**
   * R33: a request still waiting on a roster this publication supersedes has nothing left to
   * approve, so each lapses, in this publication's transaction. Stores them and nothing else; they
   * are recorded and told once the publication is stored ({@link #tellOfLapses}).
   *
   * @return the requests it lapsed
   */
  private List<SwapRequest> lapsePendingSwapsOn(
      OrganisationId organisation, List<Roster> superseded) {
    List<SwapRequest> lapsed = new ArrayList<>();
    for (Roster roster : superseded) {
      for (SwapRequest pending : swaps.findPendingNaming(organisation, roster.id())) {
        SwapRequest ended = pending.lapsed();
        swaps.save(ended);
        lapsed.add(ended);
      }
    }
    return lapsed;
  }

  /** Records each lapse, and tells both its parties (R33). */
  private void tellOfLapses(Actor actor, OrganisationId organisation, List<SwapRequest> lapsed) {
    for (SwapRequest request : lapsed) {
      recorded(actor, organisation, SchedulingChange.SWAP_LAPSED, request.id().value());
      notifier.swapLapsed(actor, organisation, request.requester(), request.id());
      notifier.swapLapsed(actor, organisation, request.counterparty(), request.id());
    }
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

  /**
   * The same judgement {@code publish} refuses by, run without the publication lock and without
   * holding the draft: it decides nothing, so it holds nothing, and its answer is advice that
   * publication checks again (ADR-042). Named {@code find…}, so it runs read-only.
   *
   * <p>It also scores the draft on the organisation's objectives (ADR-010), from the same reads, so
   * the score and the violations describe one draft as it stood at one moment.
   */
  @Override
  public RosterAssessment findAssessment(
      Actor actor, OrganisationId organisation, RosterId rosterId) {
    authorise(actor, organisation, MAY_SEE_ROSTERS);
    Roster draft =
        rosters
            .findById(organisation, rosterId)
            .orElseThrow(() -> new UnknownRosterException(rosterId));
    if (draft.status() != RosterStatus.DRAFT) {
      throw new RosterNotEditableException(
          rosterId,
          draft.status(),
          "only a draft is checked; a published roster was judged when it was published");
    }
    ConstraintSet constraints = currentOrInitial(organisation);
    List<Roster> existing = rosters.findByOrganisation(organisation);
    List<RosterCandidate> candidates = workforce.candidatesFor(actor, organisation);
    List<ConstraintViolation> violations =
        violationsOf(organisation, draft, constraints, existing, candidates);
    List<Shift> inPeriod = shifts.findWithin(organisation, draft.period());
    return new RosterAssessment(
        draft.id(),
        constraints.version(),
        violations,
        draft.shortfallsAmong(inPeriod),
        scoreOf(organisation, draft, inPeriod, candidates, existing));
  }

  /**
   * How a draft measures on the objectives, and what that comes to under its organisation's weights
   * (ADR-010).
   *
   * @param inPeriod every shift overlapping the draft's period, which holds every shift it assigns
   * @param candidates the directory's answer the draft was judged against, carrying what each
   *     person said about when they would like to work
   * @param existing every roster the organisation holds, from which the ones this would supersede
   *     are found
   */
  private RosterScore scoreOf(
      OrganisationId organisation,
      Roster draft,
      List<Shift> inPeriod,
      List<RosterCandidate> candidates,
      List<Roster> existing) {
    Map<ShiftId, Shift> byId = new LinkedHashMap<>();
    inPeriod.forEach(shift -> byId.put(shift.id(), shift));
    Map<EmployeeId, StatedPreferences> preferences = new LinkedHashMap<>();
    candidates.forEach(candidate -> preferences.put(candidate.id(), candidate.preferences()));
    return new RosterScore(
        RosterMeasures.of(draft, byId, preferences, existing), weightsOf(organisation));
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
   * Every rule a roster breaks: the one judgement publication refuses by (ADR-042).
   *
   * <p>One place at a time (R3, R35) and the working-time rules, judged by the roster from when its
   * shifts run; qualifications (R4), declared unavailability (R18) and belonging to the
   * organisation (R5), judged by {@link AssignmentEligibility} against one read of the directory.
   * Listed together, so a refusal names everything wrong at once.
   *
   * @param existing every roster the organisation holds, from which the work held beside this one
   *     is found
   * @param candidates the directory's answer for the organisation, read by the caller with the
   *     acting identity so it is scoped to them
   * @return every rule broken, empty if the roster may be published
   * @throws RosterNotVerifiableException if a shift this roster assigns, or one its people hold
   *     beside it, cannot be resolved: whatever cannot be placed in time cannot be judged
   */
  private List<ConstraintViolation> violationsOf(
      OrganisationId organisation,
      Roster roster,
      ConstraintSet constraints,
      List<Roster> existing,
      List<RosterCandidate> candidates) {
    Map<ShiftId, Shift> assigned = assignedShifts(organisation, roster);
    List<HeldWork> heldElsewhere =
        heldElsewhere(
            organisation,
            existing.stream().filter(other -> other.staysAuthoritativeBeside(roster)).toList(),
            roster.assignments().stream().map(Assignment::employee).collect(Collectors.toSet()));
    return judged(roster, constraints, assigned, heldElsewhere, candidates);
  }

  /**
   * The judgement {@link #violationsOf} makes, from evidence already resolved: nothing here reads a
   * port, so a caller holding the shifts, the work held elsewhere and the directory's answer can
   * ask it without reading any of them again.
   *
   * @param shifts every shift the roster assigns, by identity; any others are ignored
   * @param heldElsewhere work on the rosters that stay authoritative beside this one
   * @return every rule broken, empty if the roster may be published
   * @throws RosterNotVerifiableException if a shift the roster assigns is missing from {@code
   *     shifts}
   */
  private static List<ConstraintViolation> judged(
      Roster roster,
      ConstraintSet constraints,
      Map<ShiftId, Shift> shifts,
      List<HeldWork> heldElsewhere,
      List<RosterCandidate> candidates) {
    Map<ShiftId, WorkingPeriod> periods = new LinkedHashMap<>();
    shifts.forEach((id, shift) -> periods.put(id, shift.workingPeriod()));

    List<ConstraintViolation> violations = new ArrayList<>();
    violations.addAll(roster.violationsUnder(constraints, periods, heldElsewhere));
    violations.addAll(AssignmentEligibility.breachesOn(roster, shifts, candidates));
    return List.copyOf(violations);
  }

  /**
   * Every shift a roster assigns, each resolved once however many people are on it.
   *
   * <p>A shift the repository cannot return refuses rather than being left out: whatever is judged
   * against these shifts would otherwise be judged against less work than the roster carries.
   *
   * @throws RosterNotVerifiableException if any of those shifts cannot be resolved
   */
  private Map<ShiftId, Shift> assignedShifts(OrganisationId organisation, Roster roster) {
    Map<ShiftId, Shift> assigned = new LinkedHashMap<>();
    for (Assignment assignment : roster.assignments()) {
      if (!assigned.containsKey(assignment.shift())) {
        assigned.put(
            assignment.shift(),
            shifts
                .findById(organisation, assignment.shift())
                .orElseThrow(
                    () -> new RosterNotVerifiableException(roster.id(), assignment.shift())));
      }
    }
    return assigned;
  }

  /**
   * When the shifts of these assignments run, and the zone each one's days are counted in (R36):
   * neither of which the roster itself knows.
   *
   * <p>A shift the repository cannot return refuses rather than being left out: whatever is judged
   * against these periods would otherwise be judged against less work than the roster carries.
   *
   * @throws RosterNotVerifiableException if any of those shifts cannot be resolved
   */
  private Map<ShiftId, WorkingPeriod> periodsOf(
      OrganisationId organisation, Roster roster, List<Assignment> assignments) {
    Map<ShiftId, WorkingPeriod> periods = new LinkedHashMap<>();
    for (Assignment assignment : assignments) {
      Shift shift =
          shifts
              .findById(organisation, assignment.shift())
              .orElseThrow(() -> new RosterNotVerifiableException(roster.id(), assignment.shift()));
      periods.put(shift.id(), shift.workingPeriod());
    }
    return periods;
  }

  /**
   * The work these people already hold on the given authoritative rosters, with when each shift
   * runs (R35).
   *
   * <p>Only their work is resolved. A colleague's shift that cannot be resolved could sit on any
   * roster the organisation ever published, and refusing for it would block a roster that does not
   * carry that colleague, with nothing on it to fix.
   *
   * @throws RosterNotVerifiableException if one of their shifts on those rosters cannot be
   *     resolved, naming the roster carrying it: work that cannot be placed in time cannot be shown
   *     not to overlap
   */
  private List<HeldWork> heldElsewhere(
      OrganisationId organisation, List<Roster> authoritative, Set<EmployeeId> employees) {
    Map<ShiftId, TimeRange> resolved = new HashMap<>();
    List<HeldWork> held = new ArrayList<>();
    for (Roster roster : authoritative) {
      for (Assignment assignment : roster.assignments()) {
        if (!employees.contains(assignment.employee())) {
          continue;
        }
        TimeRange when =
            resolved.computeIfAbsent(
                assignment.shift(),
                shift ->
                    shifts
                        .findById(organisation, shift)
                        .map(Shift::period)
                        .orElseThrow(() -> new RosterNotVerifiableException(roster.id(), shift)));
        held.add(new HeldWork(roster.id(), assignment.shift(), assignment.employee(), when));
      }
    }
    return held;
  }

  /**
   * Marks every roster this publication replaces as superseded, so that exactly one roster is
   * authoritative for any given moment. Which rosters those are is {@link Roster#isSupersededBy}'s
   * decision.
   *
   * <p>This is the mechanism that removes the need for an override — see ADR-005 and requirement
   * R6.
   */
  private void supersede(
      Actor actor, OrganisationId organisation, List<Roster> replaced, Instant at) {
    replaced.forEach(
        existing -> {
          rosters.save(existing.superseded(at));
          // Recorded per roster, not once for the publication that caused it. Nobody asked for
          // these demotions, so without an entry each a roster appears to demote itself.
          recorded(actor, organisation, SchedulingChange.ROSTER_SUPERSEDED, existing.id().value());
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

  /** Nothing stored is the organisation that has weighted nothing, not a missing record. */
  private ObjectiveWeights weightsOf(OrganisationId organisation) {
    return objectiveWeights.find(organisation).orElseGet(() -> ObjectiveWeights.none(organisation));
  }

  private void authorise(Actor actor, OrganisationId organisation, Set<Role> permitted) {
    actor.requireAccessTo(organisation).requireRoleIn(permitted);
  }
}
