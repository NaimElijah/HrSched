package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.ArrayList;
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
   * @param shiftPeriods when each assigned shift runs; must cover every assigned shift
   * @return one schedule per employee appearing in this roster
   * @throws RosterNotVerifiableException if any assigned shift is missing from {@code shiftPeriods}
   */
  public List<EmployeeSchedule> schedulesFor(Map<ShiftId, TimeRange> shiftPeriods) {
    Objects.requireNonNull(shiftPeriods, "shiftPeriods must not be null");
    Map<EmployeeId, List<TimeRange>> byEmployee = new LinkedHashMap<>();
    for (Assignment assignment : assignments) {
      TimeRange when = shiftPeriods.get(assignment.shift());
      if (when == null) {
        throw new RosterNotVerifiableException(id, assignment.shift());
      }
      byEmployee.computeIfAbsent(assignment.employee(), key -> new ArrayList<>()).add(when);
    }
    return byEmployee.entrySet().stream()
        .map(entry -> new EmployeeSchedule(entry.getKey(), entry.getValue()))
        .toList();
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
