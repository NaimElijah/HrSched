package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.WorkforceDirectory;
import io.github.naimelijah.hrsched.scheduling.domain.model.AssigneeNotQualifiedException;
import io.github.naimelijah.hrsched.scheduling.domain.model.AssigneeUnavailableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintViolation;
import io.github.naimelijah.hrsched.scheduling.domain.model.Roster;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownAssigneeException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether one person may be put on one shift: rules R5, R4 and R18, in one place.
 *
 * <p>Extracted because every path that puts somebody on a shift decides it — assigning by hand,
 * approving a swap, and publication, which judges every assignment a draft carries however it got
 * there (ADR-042) — and a rule enforced on one of those paths is not enforced. Package-private and
 * static: it holds nothing, and it is a rule rather than a collaborator.
 *
 * <p>Working-time rules are deliberately not here. They judge a whole schedule rather than a single
 * assignment, so the roster judges them, beside its overlaps.
 *
 * <p>Unavailability <em>is</em> here, and the first version of this left it out. That was a real
 * hole: generation filters candidates through {@code RosterCandidate.couldCover}, which tests
 * availability as well as qualifications, so generation refused to roster somebody through their
 * declared absence while assigning by hand accepted it — and publication could not catch it
 * afterwards, because unavailability is not a {@code WorkingTimeConstraint} and an empty constraint
 * set checks nothing at all. A rule enforced on one of two paths is not enforced.
 */
final class AssignmentEligibility {

  private AssignmentEligibility() {}

  /**
   * Every breach of R5, R4 and R18 on a roster, listed rather than refused at the first: what
   * publication refuses, and what checking a draft reports, for each assignment the roster carries.
   *
   * <p>The same two questions {@link #require} asks of one person, asked of all of them against one
   * read of the directory, so a roster is judged against one consistent view of who may work.
   *
   * @param roster whose assignments to judge
   * @param shifts every shift the roster assigns, by identity
   * @param candidates this organisation's rosterable people
   * @return every breach, in the order of the roster's assignments; empty if there is none
   */
  static List<ConstraintViolation> breachesOn(
      Roster roster, Map<ShiftId, Shift> shifts, List<RosterCandidate> candidates) {
    Map<EmployeeId, RosterCandidate> byId = new HashMap<>();
    candidates.forEach(candidate -> byId.put(candidate.id(), candidate));
    List<ConstraintViolation> breaches = new ArrayList<>();
    for (Assignment assignment : roster.assignments()) {
      Shift shift = shifts.get(assignment.shift());
      RosterCandidate candidate = byId.get(assignment.employee());
      if (candidate == null) {
        breaches.add(
            ConstraintViolation.notOfTheOrganisation(assignment.employee(), assignment.shift()));
        continue;
      }
      Set<QualificationId> missing = candidate.lacking(shift.requiredQualifications());
      if (!missing.isEmpty()) {
        breaches.add(
            ConstraintViolation.missingQualifications(
                assignment.employee(), assignment.shift(), missing));
      }
      if (candidate.isUnavailableDuring(shift.period())) {
        breaches.add(
            ConstraintViolation.declaredUnavailable(assignment.employee(), assignment.shift()));
      }
    }
    return List.copyOf(breaches);
  }

  /**
   * Refuses anybody who may not cover this shift.
   *
   * <p>Eligibility is decided from what the workforce directory returns, carrying the actor,
   * because that is the only sense in which scheduling knows a person exists at all. Somebody who
   * does not come back through the published port is refused rather than skipped: a skipped check
   * is work on a roster whose eligibility nobody established.
   *
   * @param workforce the published view of who can be rostered
   * @param actor whoever is acting, carried through so the answer is scoped to their access
   * @param organisation whose roster
   * @param employee who is being put on the shift
   * @param shift the shift in question
   * @throws UnknownAssigneeException if they are not one of this organisation's rosterable people
   * @throws AssigneeNotQualifiedException if they lack a qualification the shift requires
   * @throws AssigneeUnavailableException if the shift runs through time they said they cannot work
   */
  static void require(
      WorkforceDirectory workforce,
      Actor actor,
      OrganisationId organisation,
      EmployeeId employee,
      Shift shift) {
    RosterCandidate candidate =
        workforce.candidatesFor(actor, organisation).stream()
            .filter(person -> person.id().equals(employee))
            .findFirst()
            .orElseThrow(() -> new UnknownAssigneeException(employee));
    // The same two questions RosterCandidate.couldCover answers for the generator, asked separately
    // so each refusal can say which one failed rather than only that the person was not eligible.
    Set<QualificationId> missing = candidate.lacking(shift.requiredQualifications());
    if (!missing.isEmpty()) {
      throw new AssigneeNotQualifiedException(employee, shift.id(), missing);
    }
    if (candidate.isUnavailableDuring(shift.period())) {
      throw new AssigneeUnavailableException(employee, shift.id());
    }
  }
}
