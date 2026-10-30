package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

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
 *
 * <p>It also records when it was published and, once superseded, when that happened. Superseding
 * adds that second moment and changes nothing already recorded, so the roster governed its days
 * from the one moment until the other, and what was authoritative at any past moment can be
 * reconstructed (R37, ADR-037).
 */
public final class Roster {

  /**
   * The finest moment history is recorded at. A moment the record cannot keep exactly would be kept
   * as a different one, so a finer one is refused rather than rounded.
   */
  private static final ChronoUnit MOMENT_OF_RECORD = ChronoUnit.MICROS;

  private final RosterId id;
  private final OrganisationId organisation;
  private final TimeRange period;
  private final RosterStatus status;
  private final List<Assignment> assignments;
  private final Integer validatedAgainstConstraintVersion;
  private final Instant publishedAt;
  private final Instant supersededAt;

  /**
   * Creates an empty draft.
   *
   * @param id the roster identity
   * @param organisation whose roster this is
   * @param period the span it covers
   */
  public Roster(RosterId id, OrganisationId organisation, TimeRange period) {
    this(id, organisation, period, RosterStatus.DRAFT, List.of(), null, null, null);
  }

  private Roster(
      RosterId id,
      OrganisationId organisation,
      TimeRange period,
      RosterStatus status,
      List<Assignment> assignments,
      Integer validatedAgainstConstraintVersion,
      Instant publishedAt,
      Instant supersededAt) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organisation = Objects.requireNonNull(organisation, "organisation must not be null");
    this.period = Objects.requireNonNull(period, "period must not be null");
    this.status = Objects.requireNonNull(status, "status must not be null");
    this.assignments = List.copyOf(assignments);
    this.validatedAgainstConstraintVersion = validatedAgainstConstraintVersion;
    // Reachable only through the transitions below, which keep it; checked so that a later edit to
    // one of them cannot quietly build a roster whose status and history disagree.
    if ((status == RosterStatus.DRAFT) != (publishedAt == null)
        || (status == RosterStatus.SUPERSEDED) != (supersededAt != null)) {
      throw new IllegalStateException(
          "Roster %s is %s, which its moments of record do not agree with".formatted(id, status));
    }
    this.publishedAt = publishedAt;
    this.supersededAt = supersededAt;
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
    return new Roster(
        id, organisation, period, status, requireNoDuplicates(extended), null, null, null);
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
    return new Roster(id, organisation, period, status, remaining, null, null, null);
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
    return new Roster(
        id, organisation, period, status, requireNoDuplicates(replacement), null, null, null);
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
   * Publishes this draft, recording which constraint version approved it and when.
   *
   * <p>The caller is responsible for having checked the constraints; this records the outcome. The
   * check lives in the application layer because it needs employee data this aggregate cannot see.
   *
   * @param constraintVersion the constraint set version that validated these assignments
   * @param at the moment it begins to govern its days; see {@link #nextMomentOfRecord}
   * @return the published roster
   * @throws RosterNotEditableException if this roster is not a draft
   * @throws IllegalArgumentException if {@code at} is finer than a microsecond
   */
  public Roster publishedUnder(int constraintVersion, Instant at) {
    requireDraft();
    return new Roster(
        id,
        organisation,
        period,
        RosterStatus.PUBLISHED,
        assignments,
        constraintVersion,
        recordable(at),
        null);
  }

  /**
   * Marks this roster as replaced by a newer one. Everything it recorded is kept, and the moment it
   * stopped governing is added.
   *
   * @param at the moment the roster replacing it was published
   * @return this roster, superseded
   * @throws RosterNotEditableException if it is not a published roster
   * @throws IllegalArgumentException if {@code at} is before it was published, or finer than a
   *     microsecond
   */
  public Roster superseded(Instant at) {
    if (status != RosterStatus.PUBLISHED) {
      throw new RosterNotEditableException(id, status, "only a published roster can be superseded");
    }
    if (recordable(at).isBefore(publishedAt)) {
      throw new IllegalArgumentException(
          "Roster %s was published at %s and cannot be superseded before that, at %s"
              .formatted(id.value(), publishedAt, at));
    }
    return new Roster(
        id,
        organisation,
        period,
        RosterStatus.SUPERSEDED,
        assignments,
        validatedAgainstConstraintVersion,
        publishedAt,
        at);
  }

  /**
   * The moment a publication records itself at, and supersedes at, given everything its
   * organisation has already recorded.
   *
   * <p>The clock's reading, to the microsecond, unless the history already reaches it: then one
   * microsecond after the latest moment recorded. A clock that is fixed, coarse, or stepped back
   * would otherwise end a roster's authority before it began, or begin and end it at one instant,
   * and the history would no longer say which roster governed when. Record time therefore only
   * moves forward within an organisation, which is what makes the half-open interval from a
   * roster's publication to its supersession a faithful account of when it governed.
   *
   * @param clock what the clock reads now
   * @param history every roster of the organisation publishing
   * @return the moment to publish and supersede at
   */
  public static Instant nextMomentOfRecord(Instant clock, Collection<Roster> history) {
    Objects.requireNonNull(clock, "clock must not be null");
    Objects.requireNonNull(history, "history must not be null");
    Instant now = clock.truncatedTo(MOMENT_OF_RECORD);
    return history.stream()
        .flatMap(roster -> Stream.of(roster.publishedAt, roster.supersededAt))
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder())
        .filter(latest -> !now.isAfter(latest))
        .map(latest -> latest.plus(1, MOMENT_OF_RECORD))
        .orElse(now);
  }

  private static Instant recordable(Instant at) {
    Objects.requireNonNull(at, "at must not be null");
    if (!at.truncatedTo(MOMENT_OF_RECORD).equals(at)) {
      throw new IllegalArgumentException(
          "%s is finer than history is recorded at, which is to the microsecond".formatted(at));
    }
    return at;
  }

  /**
   * Every rule this roster breaks that can be judged from when its shifts run, listed rather than
   * thrown at the first: one place at a time (R3), across every authoritative roster (R35), and the
   * working-time rules of {@code constraints}, each employee's schedule judged on its own.
   *
   * <p>R3 holds whatever the constraint set says, and publication cannot skip this, so a roster
   * carrying somebody in two places is refused however the draft was made. Somebody in two places
   * at once has those overlaps listed and their working time left unjudged: a schedule nobody can
   * work has no meaningful rest or daily total, and a rule reporting on it would only restate the
   * overlap. It is judged once the overlap is gone.
   *
   * <p>Work held elsewhere is checked for R3 only. It is not in the schedules judged, because the
   * working-time rules still judge one roster at a time (ADR-033).
   *
   * @param constraints the working-time rules each schedule is judged against
   * @param shiftPeriods when each assigned shift runs, in the zone its days are counted in; must
   *     cover every assigned shift
   * @param heldElsewhere work on the other rosters that stay authoritative beside this one
   * @return every breach, employee by employee in the order they were first assigned; empty if
   *     there is none
   * @throws RosterNotVerifiableException if any assigned shift is missing from {@code shiftPeriods}
   */
  public List<ConstraintViolation> violationsUnder(
      ConstraintSet constraints,
      Map<ShiftId, WorkingPeriod> shiftPeriods,
      List<HeldWork> heldElsewhere) {
    Objects.requireNonNull(constraints, "constraints must not be null");
    Objects.requireNonNull(heldElsewhere, "heldElsewhere must not be null");
    List<ConstraintViolation> violations = new ArrayList<>();
    for (EmployeeSchedule schedule : schedulesFor(shiftPeriods)) {
      List<Overlap> overlaps = overlapsOf(schedule.employee(), shiftPeriods, heldElsewhere);
      if (overlaps.isEmpty()) {
        violations.addAll(constraints.violationsFor(schedule));
      } else {
        overlaps.stream().map(Overlap::asViolation).forEach(violations::add);
      }
    }
    return List.copyOf(violations);
  }

  /**
   * Coverage: each of these shifts in this roster's period that it puts fewer people on than the
   * shift requires, and by how many.
   *
   * <p>A headcount is the least a shift needs (R22), so more people than it requires is never
   * short. Only this roster's own assignments are counted. A shift straddling into an adjacent
   * period may also be staffed there; generation carries those people onto this roster, so they
   * count here as its own (#130), while somebody put on it by hand is counted only where they are.
   *
   * @param shifts the shifts to count, usually every one overlapping this roster's period; any
   *     outside it are ignored
   * @return every short shift, in the order given; empty if none is
   */
  public List<Shortfall> shortfallsAmong(Collection<Shift> shifts) {
    Objects.requireNonNull(shifts, "shifts must not be null");
    Map<ShiftId, Integer> staffed = new HashMap<>();
    assignments.forEach(assignment -> staffed.merge(assignment.shift(), 1, Integer::sum));
    return shifts.stream()
        .filter(shift -> shift.period().overlaps(period))
        .filter(shift -> staffed.getOrDefault(shift.id(), 0) < shift.requiredHeadcount())
        .map(
            shift ->
                new Shortfall(
                    shift.id(), shift.requiredHeadcount(), staffed.getOrDefault(shift.id(), 0)))
        .toList();
  }

  /**
   * Groups the assignments into one schedule per employee, which is what working-time rules are
   * evaluated against. Says nothing about whether those schedules can be worked: {@link
   * #violationsUnder} does.
   *
   * @param shiftPeriods when each assigned shift runs, in the zone its days are counted in; must
   *     cover every assigned shift
   * @return one schedule per employee appearing in this roster, holding only this roster's work
   * @throws RosterNotVerifiableException if any assigned shift is missing from {@code shiftPeriods}
   */
  public List<EmployeeSchedule> schedulesFor(Map<ShiftId, WorkingPeriod> shiftPeriods) {
    Objects.requireNonNull(shiftPeriods, "shiftPeriods must not be null");
    Map<EmployeeId, List<WorkingPeriod>> byEmployee = new LinkedHashMap<>();
    for (Assignment assignment : assignments) {
      WorkingPeriod when = shiftPeriods.get(assignment.shift());
      if (when == null) {
        throw new RosterNotVerifiableException(id, assignment.shift());
      }
      byEmployee.computeIfAbsent(assignment.employee(), key -> new ArrayList<>()).add(when);
    }
    return byEmployee.entrySet().stream()
        .map(entry -> new EmployeeSchedule(entry.getKey(), entry.getValue()))
        .toList();
  }

  /**
   * Refuses this roster if one person would work two shifts at once (R3): two of its own, or one of
   * its own beside work they already hold on another authoritative roster (R35).
   *
   * <p>Only that person's shifts need to be in {@code shiftPeriods}, so assigning somebody by hand
   * can be checked without resolving everybody else's work. It refuses with the first overlap,
   * because one is enough to refuse an assignment; publication lists them all through {@link
   * #violationsUnder}, from the same comparison.
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
    List<Overlap> overlaps = overlapsOf(employee, shiftPeriods, heldElsewhere);
    if (!overlaps.isEmpty()) {
      throw overlaps.getFirst().asRefusal();
    }
  }

  /**
   * Two pieces of one person's work that run at once: both on this roster, or the second held on
   * another one.
   */
  private record Overlap(
      EmployeeId employee, ShiftId shift, ShiftId overlapping, Optional<RosterId> heldOn) {

    ConstraintViolation asViolation() {
      return ConstraintViolation.overlappingWork(employee, shift, overlapping, heldOn);
    }

    OverlappingAssignmentException asRefusal() {
      return heldOn
          .map(roster -> new OverlappingAssignmentException(employee, shift, overlapping, roster))
          .orElseGet(() -> new OverlappingAssignmentException(employee, shift, overlapping));
    }
  }

  /**
   * Every overlap in one person's work, in the order their shifts start.
   *
   * <p>Every pair of their own shifts is compared, not only neighbours by start time: a refusal
   * needs only one overlap, but a listing needs them all, and three shifts at once are three pairs.
   *
   * <p>A shift this roster carries for them is compared as theirs, and its copy held on another
   * roster is not compared again. R35 lets either roster carry a shift straddling both periods,
   * which is one place to be, not two (ADR-033): the copy is no overlap with itself, and a clash
   * with it is one clash, listed once. Two pieces of work held elsewhere are never compared with
   * each other: if they overlap, that is a breach of the rosters carrying them, not of this one.
   */
  private List<Overlap> overlapsOf(
      EmployeeId employee, Map<ShiftId, WorkingPeriod> shiftPeriods, List<HeldWork> heldElsewhere) {
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
    List<Overlap> overlaps = new ArrayList<>();
    for (int i = 0; i < chronological.size(); i++) {
      for (int j = i + 1; j < chronological.size(); j++) {
        ShiftId earlier = chronological.get(i);
        ShiftId later = chronological.get(j);
        if (shiftPeriods.get(earlier).period().overlaps(shiftPeriods.get(later).period())) {
          overlaps.add(new Overlap(employee, earlier, later, Optional.empty()));
        }
      }
    }
    for (HeldWork held : heldElsewhere) {
      if (!held.employee().equals(employee) || theirs.contains(held.shift())) {
        continue;
      }
      for (ShiftId shift : chronological) {
        if (shiftPeriods.get(shift).period().overlaps(held.period())) {
          overlaps.add(new Overlap(employee, shift, held.shift(), Optional.of(held.roster())));
        }
      }
    }
    return overlaps;
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

  /**
   * Refuses anything but a draft, for a caller that must know before it does anything else.
   *
   * <p>Every edit to a draft, and {@link #publishedUnder}, checks this itself. Publication needs it
   * on its own, because it writes before it reaches {@link #publishedUnder}: it supersedes other
   * rosters, and every check that can refuse runs before the first write.
   *
   * @throws RosterNotEditableException if this roster is no longer a draft
   */
  public void requireDraft() {
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

  /**
   * @return when this roster was published, or null while it is a draft
   */
  public Instant publishedAt() {
    return publishedAt;
  }

  /**
   * @return when a newer roster superseded this one, or null unless one has
   */
  public Instant supersededAt() {
    return supersededAt;
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
