package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.WorkforceDirectory;
import io.github.naimelijah.hrsched.scheduling.domain.model.AssigneeNotQualifiedException;
import io.github.naimelijah.hrsched.scheduling.domain.model.AssigneeUnavailableException;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.UnknownAssigneeException;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import java.util.Set;

/**
 * Whether one person may be put on one shift: rules R5 and R4, in one place.
 *
 * <p>Extracted because two use cases now decide it — assigning by hand, and approving a swap — and
 * a rule enforced on one of two paths is not enforced. Package-private and static: it holds
 * nothing, and it is a rule rather than a collaborator.
 *
 * <p>Working-time rules are deliberately not here. They judge a whole schedule rather than a single
 * assignment, so they belong at publication, which cannot be bypassed.
 *
 * <p>Unavailability <em>is</em> here, and the first version of this left it out. That was a real
 * hole: {@code GreedyRosterGenerator} filters candidates through {@code
 * RosterCandidate.couldCover}, which tests availability as well as qualifications, so generation
 * refused to roster somebody through their declared absence while assigning by hand accepted it —
 * and publication could not catch it afterwards, because unavailability is not a {@code
 * WorkingTimeConstraint} and an empty constraint set checks nothing at all. A rule enforced on one
 * of two paths is not enforced.
 */
final class AssignmentEligibility {

  private AssignmentEligibility() {}

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
