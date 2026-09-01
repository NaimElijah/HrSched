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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the swap use cases (requirement F7).
 *
 * <p>Its own service rather than more of {@code SchedulingService}, which already implements four
 * driving ports over six collaborators. Swapping is a distinct workflow with its own aggregate and
 * its own audience, and putting it beside roster planning would have made one class nobody can hold
 * in their head.
 *
 * <p><strong>It publishes through {@link PlanRostersUseCase} rather than repeating what publishing
 * does.</strong> That is the important decision in this class. Approving a swap has to check the
 * organisation's rules, supersede whatever it replaces, and record both — and every one of those is
 * something publication already does. Calling it means the guarantee "a roster that breaks a rule
 * cannot be published, and there is no override" is not merely also true here; it is literally the
 * same code. A second implementation would be a second thing to keep honest.
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

  public SwapService(
      SwapRequestRepository swaps,
      RosterRepository rosters,
      ShiftRepository shifts,
      WorkforceDirectory workforce,
      PlanRostersUseCase planRosters,
      Notifier notifier,
      AuditTrail audit) {
    this.swaps = Objects.requireNonNull(swaps, "swaps must not be null");
    this.rosters = Objects.requireNonNull(rosters, "rosters must not be null");
    this.shifts = Objects.requireNonNull(shifts, "shifts must not be null");
    this.workforce = Objects.requireNonNull(workforce, "workforce must not be null");
    this.planRosters = Objects.requireNonNull(planRosters, "planRosters must not be null");
    this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
    this.audit = Objects.requireNonNull(audit, "audit must not be null");
  }

  @Override
  public SwapRequestId requestSwap(Actor actor, RequestSwapCommand command) {
    authoriseSelfOrManager(actor, command.organisation(), command.requester());
    Roster published = publishedRoster(command.organisation(), command.roster());
    Assignment theirs = new Assignment(command.shift(), command.requester());
    if (!published.assignments().contains(theirs)) {
      throw new UnknownAssignmentException(published.id(), theirs);
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
            command.counterparty());
    swaps.save(request);
    recorded(actor, command.organisation(), SchedulingChange.SWAP_REQUESTED, request.id().value());
    notifier.swapRequested(actor, command.organisation(), command.counterparty(), request.id());
    return request.id();
  }

  @Override
  public List<PublishedSwapRequest> findSwapRequests(Actor actor, OrganisationId organisation) {
    actor.requireAccessTo(organisation);
    List<SwapRequest> all = swaps.findByOrganisation(organisation);
    return visibleTo(actor, all).stream().map(SwapService::publish).toList();
  }

  /**
   * A swap request names two colleagues and says what one of them wants off their plate, so it is
   * personal data in the same way a roster is. An Admin or a Scheduler sees every request because
   * answering them is their job; anybody else sees only the ones they are party to.
   */
  private static List<SwapRequest> visibleTo(Actor actor, List<SwapRequest> all) {
    if (!(actor instanceof TenantActor tenant) || MAY_ACT_FOR_OTHERS.contains(tenant.role())) {
      return all;
    }
    return tenant
        .employee()
        .map(me -> all.stream().filter(request -> request.involves(me)).toList())
        .orElseGet(List::of);
  }

  @Override
  public Roster approveSwap(Actor actor, OrganisationId organisation, SwapRequestId id) {
    authorise(actor, organisation, MAY_DECIDE);
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

    // Publishing is what checks the rules, supersedes what it replaces, and records both. If the
    // swap would break a rule this throws and nothing below runs - the request stays pending, and
    // the replacement stays a draft the Scheduler can look at to see what was refused.
    Roster publishedReplacement = planRosters.publish(actor, organisation, replacement.id());

    swaps.save(request.approved());
    recorded(actor, organisation, SchedulingChange.SWAP_APPROVED, request.id().value());
    notifier.swapApproved(actor, organisation, request.counterparty(), request.id());
    notifier.swapApproved(actor, organisation, request.requester(), request.id());
    return publishedReplacement;
  }

  @Override
  public void rejectSwap(Actor actor, OrganisationId organisation, SwapRequestId id) {
    authorise(actor, organisation, MAY_DECIDE);
    SwapRequest request = pending(organisation, id);

    swaps.save(request.rejected());
    recorded(actor, organisation, SchedulingChange.SWAP_REJECTED, request.id().value());
    notifier.swapRejected(actor, organisation, request.requester(), request.id());
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
   * <p>The status is checked here, before the roster is, so that deciding a swap twice is reported
   * as "already decided" rather than as whatever became of the roster in the meantime — which after
   * an approval is that it was superseded, an answer that describes a symptom and hides the cause.
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
    actor.requireAccessTo(organisation);
    if (actor instanceof TenantActor tenant && !tenant.isThemself(subject)) {
      tenant.requireRoleIn(MAY_ACT_FOR_OTHERS);
    }
  }

  private static void authorise(Actor actor, OrganisationId organisation, Set<Role> permitted) {
    actor.requireAccessTo(organisation);
    if (actor instanceof TenantActor tenant) {
      tenant.requireRoleIn(permitted);
    }
  }

  /** Called after the repository, never before: see ADR-018. */
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
