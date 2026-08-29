package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Requires a minimum gap between two consecutive periods of work.
 *
 * <p>The threshold is configuration. Most jurisdictions mandate one, and they disagree about what
 * it is, which is exactly why it is not written here.
 *
 * @param minimumRest the shortest permitted gap, strictly positive
 */
public record MinimumRestBetweenAssignments(Duration minimumRest) implements WorkingTimeConstraint {

  public MinimumRestBetweenAssignments {
    Objects.requireNonNull(minimumRest, "minimumRest must not be null");
    if (minimumRest.isNegative() || minimumRest.isZero()) {
      throw new InvalidConstraintException("Minimum rest must be positive, but got " + minimumRest);
    }
  }

  @Override
  public String name() {
    return "minimum rest between assignments";
  }

  @Override
  public Optional<String> violatedBy(EmployeeSchedule schedule) {
    List<TimeRange> periods = schedule.workingPeriods();
    for (int i = 1; i < periods.size(); i++) {
      TimeRange previous = periods.get(i - 1);
      TimeRange next = periods.get(i);
      if (previous.overlaps(next)) {
        return Optional.of("assignments %s and %s overlap".formatted(previous, next));
      }
      Duration rest = previous.gapTo(next);
      if (rest.compareTo(minimumRest) < 0) {
        return Optional.of(
            "only %s rest between %s and %s, but %s is required"
                .formatted(rest, previous, next, minimumRest));
      }
    }
    return Optional.empty();
  }
}
