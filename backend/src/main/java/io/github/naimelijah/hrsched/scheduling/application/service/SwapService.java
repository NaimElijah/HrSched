package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.in.PlanRostersUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.in.PublishedSwapRequest;
import io.github.naimelijah.hrsched.scheduling.application.port.in.RequestSwapCommand;
import io.github.naimelijah.hrsched.scheduling.application.port.in.RequestSwapsUseCase;
import io.github.naimelijah.hrsched.scheduling.application.port.out.AuditTrail;
import io.github.naimelijah.hrsched.scheduling.application.port.out.AuditedChange;
import io.github.naimelijah.hrsched.scheduling.application.port.out.Notifier;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.SchedulingChange;
import io.github.naimelijah.hrsched.scheduling.application.port.out.ShiftRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.SwapRequestRepository;
import io.github.naimelijah.hrsched.scheduling.application.port.out.WorkforceDirectory;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterNotEditableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.RosterStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapAlreadyRequestedException;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapNotPendingException;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequest;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestStatus;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownAssignmentException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownRosterException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownShiftException;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownSwapRequestException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.Role;
import io.github.naimelijah.hrsched.shared.domain.model.TenantActor;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the swap use cases (requirement F7).
 *
 * <p>Its own service rather than more of {@code SchedulingService}, which already implements every
 * other driving port of this context and holds what each of them needs. Swapping is a distinct
 * workflow with its own aggregate and its own audience, and putting it beside roster planning would
 * have made one class nobody can hold in their head.
 *
 * <p><strong>It publishes through {@link PlanRostersUseCase} rather than repeating what publishing
 * does.</strong> That is the important decision in this class. Approving a swap has to check the
 * organisation's rules, supersede whatever it replaces, and record both — and every one of those is
 * something publication already does. Calling it means the guarantee "a roster that breaks a rule
 * cannot be published, and there is no override" is not merely also true here; it is literally the
 * same code. A second implementation would be a second thing to keep honest.
 *
 * <p>Takes a {@link Clock} rather than calling {@code Instant.now()}, so when a request was raised
 * is an input a test supplies. It matters more here than it looks: that time is what the repository
 * orders a Scheduler's queue by.
 */
public class SwapService implements RequestSwapsUseCase {

  /** Deciding a swap is building a roster, which belongs to the Scheduler alone (Q11). */
  private static final Set<Role> MAY_DECIDE = Set.of(Role.SCHEDULER);

  /** Who may raise a request on somebody else's behalf, and who may see every request. */
  private static final Set<Role> MAY_ACT_FOR_OTHERS = Set.of(Role.ADMIN, Role.SCHEDULER);

  private final SwapRequestRepository swaps;
  private final RosterRepository rosters;
  private final ShiftRepository shifts;
  private final WorkforceDirectory workforce;
  private final PlanRostersUseCase planRosters;
  private final Notifier notifier;
  private final AuditTrail audit;
  private final Clock clock;

  /**
   * @param swaps where swap requests are stored
   * @param rosters where the roster a request names, and its replacement, are stored
   * @param shifts where the shift a request names is read
   * @param workforce who may be put on a shift
   * @param planRosters publishes the replacement roster an approval builds, and so checks its rules
   * @param notifier how the parties to a request are told of its being raised, approved or rejected
   * @param audit where each swap request, approval and rejection is recorded
   * @param clock what a request's time of raising is read from
   * @throws NullPointerException if any argument is null
   */
  public SwapService(
      SwapRequestRepository swaps,
      RosterRepository rosters,
      ShiftRepository shifts,
      WorkforceDirectory workforce,
      PlanRostersUseCase planRosters,
      Notifier notifier,
      AuditTrail audit,
      Clock clock) {
    this.swaps = Objects.requireNonNull(swaps, "swaps must not be null");
    this.rosters = Objects.requireNonNull(rosters, "rosters must not be null");
    this.shifts = Objects.requireNonNull(shifts, "shifts must not be null");
    this.workforce = Objects.requireNonNull(workforce, "workforce must not be null");
    this.planRosters = Objects.requireNonNull(planRosters, "planRosters must not be null");
    this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
    this.audit = Objects.requireNonNull(audit, "audit must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public SwapRequestId requestSwap(Actor actor, RequestSwapCommand command) {
    authoriseSelfOrManager(actor, command.organisation(), command.requester());
    // Before the roster is read. A publication in flight may be about to supersede it, and a
    // request stored after that publication looked for requests to lapse would wait for ever on a
    // roster nobody works to (R33, ADR-039).
    rosters.lockPublicationsOf(command.organisation());
    Roster published = publishedRoster(command.organisation(), command.roster());
    Assignment theirs = new Assignment(command.shift(), command.requester());
    if (!published.assignments().contains(theirs)) {
      throw new UnknownAssignmentException(published.id(), theirs);
    }
    // R32: one pending request per assignment, whoever it asks. Checked under the lock taken above,
    // so two asks at once take turns; the schema refuses a writer that skipped it.
    if (swaps.findPendingNaming(command.organisation(), command.roster()).stream()
        .anyMatch(pending -> pending.given().equals(theirs))) {
      throw new SwapAlreadyRequestedException(command.roster(), command.shift());
    }
    // Eligibility is deliberately NOT checked here. Deciding whether a colleague may cover a shift
    // means reading the workforce directory, and an Employee may not - listing colleagues discloses
    // their unavailability, which is personal data (R12's reasoning). Asking is not deciding: the
    // Scheduler who approves does have that access, and approveSwap checks it there, which is the
    // only point at which the answer has to be true anyway.
    SwapRequest request =
        new SwapRequest(
            SwapRequestId.newId(),
            command.organisation(),
            command.roster(),
            command.shift(),
            command.requester(),
            command.counterparty(),
            clock.instant());
    swaps.save(request);
    notifier.swapRequested(actor, command.organisation(), command.counterparty(), request.id());
    recorded(actor, command.organisation(), SchedulingChange.SWAP_REQUESTED, request.id().value());
    return request.id();
  }

  @Override
  public List<PublishedSwapRequest> findSwapRequests(Actor actor, OrganisationId organisation) {
    TenantActor member = actor.requireAccessTo(organisation);
    return swaps.findByOrganisation(organisation).stream()
        .filter(request -> isVisibleTo(member, request))
        .map(SwapService::publish)
        .toList();
  }

  @Override
  public Optional<PublishedSwapRequest> findSwapRequest(
      Actor actor, OrganisationId organisation, SwapRequestId id) {
    TenantActor member = actor.requireAccessTo(organisation);
    return swaps
        .findById(organisation, id)
        .filter(request -> isVisibleTo(member, request))
        .map(SwapService::publish);
  }

  /**
   * A swap request names two colleagues and says what one of them wants off their plate, so it is
   * personal data in the same way a roster is. An Admin or a Scheduler sees every request because
   * answering them is their job; anybody else sees only the ones they are party to.
   *
   * <p>One predicate serves both the listing and reading one, so the two cannot disagree about who
   * may see a request.
   */
  private static boolean isVisibleTo(TenantActor actor, SwapRequest request) {
    if (MAY_ACT_FOR_OTHERS.contains(actor.role())) {
      return true;
    }
    return actor.employee().map(request::involves).orElse(false);
  }

  @Override
  public Roster approveSwap(Actor actor, OrganisationId organisation, SwapRequestId id) {
    authorise(actor, organisation, MAY_DECIDE);
    // Before the request and its roster are read, so an approval that waited for another decides
    // from what that one committed rather than building on a roster it has just replaced. The
    // publication below takes the same lock again, and already holds it (ADR-027).
    rosters.lockPublicationsOf(organisation);
    SwapRequest request = pending(organisation, id);
    Roster current = publishedRoster(organisation, request.roster());
    if (!current.assignments().contains(request.given())) {
      throw new UnknownAssignmentException(current.id(), request.given());
    }
    AssignmentEligibility.require(
        workforce,
        actor,
        organisation,
        request.counterparty(),
        shiftOrUnknown(organisation, request.shift()));

    Roster replacement =
        new Roster(RosterId.newId(), organisation, current.period())
            .withAssignments(swapped(current, request));
    rosters.save(replacement);

    // Publishing is what checks the rules, supersedes what it replaces, and records both. It stores
    // the approval too, before lapsing the other requests on the roster it supersedes, so this one
    // is not lapsed with them (R33, ADR-039), and tells both parties of it before it records
    // anything, because a telling is a write that can still fail (#133). If the swap would break a
    // rule this throws and nothing below runs, so the request stays pending; the use case's
    // transaction then takes the replacement draft with it, leaving nothing half-built (ADR-023).
    Roster publishedReplacement =
        planRosters.publishApproving(actor, organisation, replacement.id(), request.approved());

    recorded(actor, organisation, SchedulingChange.SWAP_APPROVED, request.id().value());
    return publishedReplacement;
  }

  @Override
  public void rejectSwap(Actor actor, OrganisationId organisation, SwapRequestId id) {
    authorise(actor, organisation, MAY_DECIDE);
    // Takes turns with publications, as approving does. A publication lapses what it reads as
    // pending, and a rejection committing in between would turn that lapse into a decision over a
    // decision, refused along with the whole publication.
    rosters.lockPublicationsOf(organisation);
    SwapRequest request = pending(organisation, id);

    swaps.save(request.rejected());
    // Both parties, as on approval. The colleague was told they were being asked to cover a shift;
    // telling only the requester leaves them holding an open question nobody ever answers.
    notifier.swapRejected(actor, organisation, request.requester(), request.id());
    notifier.swapRejected(actor, organisation, request.counterparty(), request.id());
    recorded(actor, organisation, SchedulingChange.SWAP_REJECTED, request.id().value());
  }

  /** The roster the replacement should carry: the same work, with one assignment handed over. */
  private static List<Assignment> swapped(Roster current, SwapRequest request) {
    List<Assignment> assignments = new ArrayList<>(current.assignments());
    assignments.remove(request.given());
    assignments.add(request.taken());
    return assignments;
  }

  /**
   * Loads a request that is still waiting on somebody.
   *
   * <p>The status is checked here, before the roster is, so that deciding a swap that has ended is
   * reported as no longer pending rather than as whatever became of the roster in the meantime -
   * which after an approval or a lapse is that it was superseded, an answer that describes a
   * symptom and hides the cause.
   *
   * <p>{@code SwapRequest.approved()} enforces the same rule and remains the invariant; this is
   * about which refusal the caller is given first.
   */
  private SwapRequest pending(OrganisationId organisation, SwapRequestId id) {
    SwapRequest request =
        swaps.findById(organisation, id).orElseThrow(() -> new UnknownSwapRequestException(id));
    if (request.status() != SwapRequestStatus.REQUESTED) {
      throw new SwapNotPendingException(id, request.status());
    }
    return request;
  }

  /**
   * A swap only means anything against the roster that is currently authoritative. One raised
   * against a draft would bind nobody, and one raised against a superseded roster is asking to
   * change a week that has already been replaced.
   */
  private Roster publishedRoster(OrganisationId organisation, RosterId id) {
    Roster roster =
        rosters.findById(organisation, id).orElseThrow(() -> new UnknownRosterException(id));
    if (roster.status() != RosterStatus.PUBLISHED) {
      throw new RosterNotEditableException(
          id, roster.status(), "a swap only applies to the roster that is currently published");
    }
    return roster;
  }

  private Shift shiftOrUnknown(
      OrganisationId organisation,
      io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId shift) {
    return shifts.findById(organisation, shift).orElseThrow(() -> new UnknownShiftException(shift));
  }

  /**
   * Raising a request for yourself needs no role; raising one for somebody else is a manager's act.
   * The link is the personnel record the actor carries, never a {@code UserId} compared with an
   * {@code EmployeeId} (ADR-012).
   */
  private static void authoriseSelfOrManager(
      Actor actor, OrganisationId organisation, EmployeeId subject) {
    TenantActor tenant = actor.requireAccessTo(organisation);
    if (!tenant.isThemself(subject)) {
      tenant.requireRoleIn(MAY_ACT_FOR_OTHERS);
    }
  }

  private static void authorise(Actor actor, OrganisationId organisation, Set<Role> permitted) {
    actor.requireAccessTo(organisation).requireRoleIn(permitted);
  }

  /**
   * Called once every write the use case makes has run, its notifications included, never before:
   * an audit line outlives the rollback of a write that fails after it (ADR-018, #133).
   */
  private void recorded(
      Actor actor, OrganisationId organisation, SchedulingChange change, UUID subject) {
    audit.record(new AuditedChange(organisation, actor.id(), change, subject));
  }

  private static PublishedSwapRequest publish(SwapRequest request) {
    return new PublishedSwapRequest(
        request.id(),
        request.roster(),
        request.shift(),
        request.requester(),
        request.counterparty(),
        request.status());
  }
}
