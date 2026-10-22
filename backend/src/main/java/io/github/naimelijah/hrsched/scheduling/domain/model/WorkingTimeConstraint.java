package io.github.naimelijah.hrsched.scheduling.domain.model;

import java.util.Optional;

/**
 * A rule an employee schedule must satisfy.
 *
 * <p>THE CENTRAL POINT OF THIS MODEL: a constraint carries its own threshold as data. There is no
 * number from any statute anywhere in this package, and there must never be one. A minimum rest
 * period is a value an organisation configures, not a constant a developer wrote down.
 *
 * <p>That matters because HrSched is sold to organisations operating under different law. A
 * jurisdiction — Israel, the EU directive, the UK regulations, a US state — is expressed as a
 * *preset*: a named set of these constraints with particular values, supplied as data and sourced
 * from the statute. A set belonging to no jurisdiction at all is equally valid.
 *
 * <p>Sealed so that the constraints the system can express are enumerable in one place, which is
 * what lets a configuration format and the solver be written against them exhaustively: the
 * solver's model states each kind in a {@code switch} with no default (ADR-044).
 *
 * <p>Every constraint here is HARD. A schedule either satisfies it or the roster cannot be
 * published; there is no override and no weighting. What a generator should merely *prefer* is a
 * separate concept entirely — see {@link Objective} and {@link ObjectiveWeights} (ADR-010).
 */
public sealed interface WorkingTimeConstraint
    permits MinimumRestBetweenAssignments,
        MaximumDailyWorkingTime,
        MaximumWeeklyWorkingTime,
        MaximumConsecutiveWorkingDays {

  /**
   * @return a short name for this rule, for reporting which one was broken
   */
  String name();

  /**
   * @param schedule everything one employee is asked to work
   * @return why this rule is broken, or empty if it is satisfied
   */
  Optional<String> violatedBy(EmployeeSchedule schedule);
}
