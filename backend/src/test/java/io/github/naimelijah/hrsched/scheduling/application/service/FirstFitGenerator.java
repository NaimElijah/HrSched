package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterCandidate;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.ConstraintSet;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import java.util.ArrayList;
import java.util.List;

/**
 * A generator that fills each shift with the first candidates who could cover it.
 *
 * <p>Hand-written, like every other double here. It stands in for the real generator so the use
 * case tests are about the use case rather than about what the greedy adapter happens to produce.
 *
 * <p>Shared by the tests in this package rather than nested in one of them, the same arrangement
 * workforce uses for its audit double: the callers are in this package, so it crosses no boundary.
 * Named without a Test suffix so Surefire does not try to run it.
 */
final class FirstFitGenerator implements RosterGenerator {

  @Override
  public List<Assignment> generate(
      List<Shift> shifts, List<RosterCandidate> candidates, ConstraintSet constraints) {
    List<Assignment> assignments = new ArrayList<>();
    for (Shift shift : shifts) {
      candidates.stream()
          .filter(candidate -> candidate.couldCover(shift.period(), shift.requiredQualifications()))
          .limit(shift.requiredHeadcount())
          .forEach(candidate -> assignments.add(new Assignment(shift.id(), candidate.id())));
    }
    return List.copyOf(assignments);
  }
}
