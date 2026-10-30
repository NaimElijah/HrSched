package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The working-time rules one organisation is bound by, at one point in time.
 *
 * <p>Versioned, and never mutated. Changing the rules produces a new version rather than altering
 * the old one, because a published roster records the version it was validated against and must
 * stay valid against that forever. Tightening a rule today cannot make last month illegal.
 *
 * <p>An empty set enforces nothing. That is a legitimate configuration — an organisation with no
 * statutory constraints, or one still being set up — and not a special case in the code.
 *
 * <p>It holds at most one rule of each kind (R24). Two would both be enforced, yet read back as one
 * threshold per kind, so a caller resending what it read would drop one without seeing it.
 *
 * @param organisation whose rules these are
 * @param version which revision of that organisation's rules this is, starting at one
 * @param constraints the rules themselves, each carrying its own threshold, at most one of a kind
 * @throws InvalidConstraintException if the version is below one, or two rules are of one kind
 */
public record ConstraintSet(
    OrganisationId organisation, int version, List<WorkingTimeConstraint> constraints) {

  public ConstraintSet {
    Objects.requireNonNull(organisation, "organisation must not be null");
    if (version < 1) {
      throw new InvalidConstraintException("A constraint set version starts at one");
    }
    constraints = List.copyOf(constraints);
    Set<Class<?>> kinds = new HashSet<>();
    for (WorkingTimeConstraint constraint : constraints) {
      if (!kinds.add(constraint.getClass())) {
        throw new InvalidConstraintException(
            "A constraint set holds at most one rule of each kind, and %s is given twice"
                .formatted(constraint.name()));
      }
    }
  }

  /**
   * @param organisation whose rules these are
   * @return an empty first version, enforcing nothing
   */
  public static ConstraintSet initial(OrganisationId organisation) {
    return new ConstraintSet(organisation, 1, List.of());
  }

  /**
   * @param replacement the rules that should apply from now on
   * @return the next version of this organisation's rules
   */
  public ConstraintSet revisedTo(List<WorkingTimeConstraint> replacement) {
    return new ConstraintSet(organisation, version + 1, replacement);
  }

  /**
   * Checks one employee's proposed work against every rule.
   *
   * @param schedule what the employee is being asked to work
   * @return every rule broken, empty if the schedule is acceptable
   */
  public List<ConstraintViolation> violationsFor(EmployeeSchedule schedule) {
    Objects.requireNonNull(schedule, "schedule must not be null");
    return constraints.stream()
        .flatMap(
            constraint ->
                constraint
                    .violatedBy(schedule)
                    .map(
                        detail ->
                            new ConstraintViolation(schedule.employee(), constraint.name(), detail))
                    .stream())
        .toList();
  }
}
