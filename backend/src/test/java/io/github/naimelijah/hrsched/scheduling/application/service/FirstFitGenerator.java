package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.GenerationRequest;
import io.github.naimelijah.hrsched.scheduling.application.port.out.RosterGenerator;
import io.github.naimelijah.hrsched.scheduling.domain.model.Assignment;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import java.util.ArrayList;
import java.util.List;

/**
 * A generator that fills each shift's places left with the first candidates who could cover it and
 * hold nothing elsewhere that overlaps it.
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
  public List<Assignment> generate(GenerationRequest request) {
    List<Assignment> assignments = new ArrayList<>();
    for (Shift shift : request.shifts()) {
      long carried =
          request.draft().assignments().stream()
              .filter(assignment -> assignment.shift().equals(shift.id()))
              .count();
      request.candidates().stream()
          .filter(
              candidate ->
                  !request
                      .draft()
                      .assignments()
                      .contains(new Assignment(shift.id(), candidate.id())))
          .filter(candidate -> candidate.couldCover(shift.period(), shift.requiredQualifications()))
          .filter(
              candidate ->
                  request.heldElsewhere().stream()
                      .noneMatch(
                          work ->
                              work.employee().equals(candidate.id())
                                  && !work.shift().equals(shift.id())
                                  && work.period().overlaps(shift.period())))
          .limit(Math.max(0, shift.requiredHeadcount() - carried))
          .forEach(candidate -> assignments.add(new Assignment(shift.id(), candidate.id())));
    }
    return List.copyOf(assignments);
  }
}
