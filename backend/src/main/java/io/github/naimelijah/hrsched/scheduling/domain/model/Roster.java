package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Who works which shifts over a period: the aggregate root of the scheduling context.
 *
 * <p>Immutable, and moves through {@link RosterStatus#DRAFT} to {@link RosterStatus#PUBLISHED} to
 * {@link RosterStatus#SUPERSEDED}. A published roster is never edited. People arrange their lives
 * around it, so changing it means publishing a new one that supersedes it, which is also how
 * emergency cover is handled: there is no override because there does not need to be one.
 *
 * <p>A published roster records the constraint set version that validated it. If the organisation
 * later tightens a rule, this roster remains a historical fact that was legal when made. Rule
 * changes are never retroactive.
 */
public final class Roster {

  private final RosterId id;
  private final OrganisationId organisation;
  private final TimeRange period;
  private final RosterStatus status;
  private final List<Assignment> assignments;
  private final Integer validatedAgainstConstraintVersion;

  /**
   * Creates an empty draft.
   *
   * @param id the roster identity
   * @param organisation whose roster this is
   * @param period the span it covers
   */
  public Roster(RosterId id, OrganisationId organisation, TimeRange period) {
    this(id, organisation, period, RosterStatus.DRAFT, List.of(), null);
  }

  private Roster(
      RosterId id,
      OrganisationId organisation,
      TimeRange period,
      RosterStatus status,
      List<Assignment> assignments,
      Integer validatedAgainstConstraintVersion) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organisation = Objects.requireNonNull(organisation, "organisation must not be null");
    this.period = Objects.requireNonNull(period, "period must not be null");
    this.status = Objects.requireNonNull(status, "status must not be null");
    this.assignments = List.copyOf(assignments);
    this.validatedAgainstConstraintVersion = validatedAgainstConstraintVersion;
  }

  /**
   * @param assignment who works which shift
   * @return a draft including that assignment
   * @throws RosterNotEditableException if this roster is no longer a draft
   * @throws DuplicateAssignmentException if that employee is already on that shift
   */
  public Roster with(Assignment assignment) {
    Objects.requireNonNull(assignment, "assignment must not be null");
    requireDraft();
    List<Assignment> extended = new ArrayList<>(assignments);
    extended.add(assignment);
    return new Roster(id, organisation, period, status, requireNoDuplicates(extended), null);
  }

  /**
   * @param assignment the assignment to take off this draft
   * @return a draft without it
   * @throws RosterNotEditableException if this roster is no longer a draft
   * @throws UnknownAssignmentException if it does not carry that assignment
   */
  public Roster without(Assignment assignment) {
    Objects.requireNonNull(assignment, "assignment must not be null");
    requireDraft();
    if (!assignments.contains(assignment)) {
      throw new UnknownAssignmentException(id, assignment);
    }
    List<Assignment> remaining = new ArrayList<>(assignments);
    remaining.remove(assignment);
    return new Roster(id, organisation, period, status, remaining, null);
  }

  /**
   * @param replacement the assignments this draft should carry instead
   * @return a draft with exactly those assignments
   * @throws RosterNotEditableException if this roster is no longer a draft
   * @throws DuplicateAssignmentException if the same employee appears twice on one shift
   */
  public Roster withAssignments(List<Assignment> replacement) {
    Objects.requireNonNull(replacement, "replacement must not be null");
    requireDraft();
    return new Roster(id, organisation, period, status, requireNoDuplicates(replacement), null);
  }

  /**
   * Guards the same invariant whichever way assignments arrive, because generation replaces the
   * list wholesale while manual editing appends one at a time, and an invariant enforced on only
   * one of two paths is not enforced.
   */
  private static List<Assignment> requireNoDuplicates(List<Assignment> candidates) {
    Set<Assignment> seen = new HashSet<>();
    for (Assignment assignment : candidates) {
      if (!seen.add(assignment)) {
        throw new DuplicateAssignmentException(assignment);
      }
    }
    return candidates;
  }

  /**
   * Publishes this draft, recording which constraint version approved it.
   *
   * <p>The caller is responsible for having checked the constraints; this records the outcome. The
   * check lives in the application layer because it needs employee data this aggregate cannot see.
   *
   * @param constraintVersion the constraint set version that validated these assignments
   * @return the published roster
   * @throws RosterNotEditableException if this roster is not a draft
   */
  public Roster publishedUnder(int constraintVersion) {
    requireDraft();
    return new Roster(
        id, organisation, period, RosterStatus.PUBLISHED, assignments, constraintVersion);
  }

  /**
   * @return this roster marked as replaced by a newer one
   * @throws RosterNotEditableException if it was never published
   */
  public Roster superseded() {
    if (status != RosterStatus.PUBLISHED) {
      throw new RosterNotEditableException(id, status, "only a published roster can be superseded");
    }
    return new Roster(
        id,
        organisation,
        period,
        RosterStatus.SUPERSEDED,
        assignments,
        validatedAgainstConstraintVersion);
  }

  /**
   * Groups the assignments into one schedule per employee, which is what constraints are evaluated
   * against.
   *
   * <p>Nobody's schedule is built with two shifts at once in it, on this roster or beside work they
   * hold on another authoritative one. R3 holds whatever the constraint set says, and this is the
   * step publication cannot skip, so it is where a roster carrying somebody in two places is
   * refused however the draft was made.
   *
   * <p>Work held elsewhere is checked for R3 only. It is not in the schedules returned, because the
   * working-time rules still judge one roster at a time (ADR-033).
   *
   * @param shiftPeriods when each assigned shift runs, in the zone its days are counted in; must
   *     cover every assigned shift
   * @param heldElsewhere work on the other rosters that stay authoritative beside this one
   * @return one schedule per employee appearing in this roster, holding only this roster's work
   * @throws RosterNotVerifiableException if any assigned shift is missing from {@code shiftPeriods}
   * @throws OverlappingAssignmentException if anybody would work two shifts at once
   */
  public List<EmployeeSchedule> schedulesFor(
      Map<ShiftId, WorkingPeriod> shiftPeriods, List<HeldWork> heldElsewhere) {
    Objects.requireNonNull(shiftPeriods, "shiftPeriods must not be null");
    Objects.requireNonNull(heldElsewhere, "heldElsewhere must not be null");
    Map<EmployeeId, List<WorkingPeriod>> byEmployee = new LinkedHashMap<>();
    for (Assignment assignment : assignments) {
      WorkingPeriod when = shiftPeriods.get(assignment.shift());
      if (when == null) {
        throw new RosterNotVerifiableException(id, assignment.shift());
      }
      byEmployee.computeIfAbsent(assignment.employee(), key -> new ArrayList<>()).add(when);
    }
    byEmployee
        .keySet()
        .forEach(employee -> requireNoOverlappingWorkFor(employee, shiftPeriods, heldElsewhere));
    return byEmployee.entrySet().stream()
        .map(entry -> new EmployeeSchedule(entry.getKey(), entry.getValue()))
        .toList();
  }

  /**
   * Refuses this roster if one person would work two shifts at once (R3): two of its own, or one of
   * its own beside work they already hold on another authoritative roster (R35).
   *
   * <p>Only that person's shifts need to be in {@code shiftPeriods}, so assigning somebody by hand
   * can be checked without resolving everybody else's work.
   *
   * <p>The same shift held by the same person on another roster is no overlap. R35 lets either
   * roster carry a shift straddling both periods, and that is one place to be, not two (ADR-033).
   * Two pieces of work held elsewhere are never compared with each other: if they overlap, that is
   * a breach of the rosters carrying them, not of this one.
   *
   * @param employee whose work to check
   * @param shiftPeriods when shifts run; must cover every shift this employee is assigned
   * @param heldElsewhere work on the other rosters that stay authoritative beside this one; anybody
   *     else's is ignored
   * @throws RosterNotVerifiableException if one of their shifts is missing from {@code
   *     shiftPeriods}
   * @throws OverlappingAssignmentException naming two shifts of theirs that overlap
   */
  public void requireNoOverlappingWorkFor(
      EmployeeId employee, Map<ShiftId, WorkingPeriod> shiftPeriods, List<HeldWork> heldElsewhere) {
    Objects.requireNonNull(employee, "employee must not be null");
    Objects.requireNonNull(shiftPeriods, "shiftPeriods must not be null");
    Objects.requireNonNull(heldElsewhere, "heldElsewhere must not be null");
    List<ShiftId> theirs =
        assignments.stream()
            .filter(assignment -> assignment.employee().equals(employee))
            .map(Assignment::shift)
            .toList();
    for (ShiftId shift : theirs) {
      if (!shiftPeriods.containsKey(shift)) {
        throw new RosterNotVerifiableException(id, shift);
      }
    }
    List<ShiftId> chronological =
        theirs.stream()
            .sorted(Comparator.comparing(shift -> shiftPeriods.get(shift).period().start()))
            .toList();
    // Sorted by start, an overlap anywhere implies one between neighbours: if a shift overlaps a
    // later one, it also overlaps every shift starting between them. So neighbours are enough.
    for (int i = 1; i < chronological.size(); i++) {
      ShiftId earlier = chronological.get(i - 1);
      ShiftId later = chronological.get(i);
      if (shiftPeriods.get(earlier).period().overlaps(shiftPeriods.get(later).period())) {
        throw new OverlappingAssignmentException(employee, earlier, later);
      }
    }
    // Every own shift against every held one. The neighbour shortcut above is unsound here: it
    // assumes every adjacent pair is compared, and pairs held elsewhere are deliberately not.
    for (HeldWork held : heldElsewhere) {
      if (!held.employee().equals(employee)) {
        continue;
      }
      for (ShiftId shift : theirs) {
        if (!shift.equals(held.shift())
            && shiftPeriods.get(shift).period().overlaps(held.period())) {
          throw new OverlappingAssignmentException(employee, shift, held.shift(), held.roster());
        }
      }
    }
  }

  /**
   * Whether publishing {@code replacement} demotes this roster (R11). Overlap, not equality, is
   * what decides, and the whole roster goes, not only the days replaced (Q16).
   *
   * @param replacement the draft about to be published
   * @return true if this is a published roster of the same organisation whose period overlaps it
   */
  public boolean isSupersededBy(Roster replacement) {
    Objects.requireNonNull(replacement, "replacement must not be null");
    return isPublishedBeside(replacement) && period.overlaps(replacement.period);
  }

  /**
   * Whether this roster still governs its days once {@code replacement} is published, so the work
   * it carries is evidence the replacement must be judged against (R35).
   *
   * @param replacement the draft about to be published
   * @return true if this is a published roster of the same organisation that it does not supersede
   */
  public boolean staysAuthoritativeBeside(Roster replacement) {
    Objects.requireNonNull(replacement, "replacement must not be null");
    return isPublishedBeside(replacement) && !period.overlaps(replacement.period);
  }

  private boolean isPublishedBeside(Roster replacement) {
    return status == RosterStatus.PUBLISHED
        && !id.equals(replacement.id)
        && organisation.equals(replacement.organisation);
  }

  private void requireDraft() {
    if (status != RosterStatus.DRAFT) {
      throw new RosterNotEditableException(
          id, status, "a roster can only be changed while a draft");
    }
  }

  public RosterId id() {
    return id;
  }

  public OrganisationId organisation() {
    return organisation;
  }

  public TimeRange period() {
    return period;
  }

  public RosterStatus status() {
    return status;
  }

  public List<Assignment> assignments() {
    return assignments;
  }

  /**
   * @return the constraint set version this roster was published under, or null while it is a draft
   */
  public Integer validatedAgainstConstraintVersion() {
    return validatedAgainstConstraintVersion;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Roster roster && id.equals(roster.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "Roster[id=%s, status=%s, assignments=%d]".formatted(id, status, assignments.size());
  }
}
