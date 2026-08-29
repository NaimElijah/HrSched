package io.github.naimelijah.hrsched.workforce.domain.model;

import java.util.Objects;

/**
 * A person who can be rostered.
 *
 * <p>An entity, not a value object: two employees who happen to share a name are still different
 * people, so equality is by {@link EmployeeId} alone. This is why it is a class and not a record.
 *
 * <p>DELIBERATELY ALMOST EMPTY, AND AHEAD OF ITS REQUIREMENTS. An employee will eventually carry
 * contracted hours, qualifications, availability, and employment status — every one of which
 * depends on Q2 (roles) or Q4 (working-time jurisdiction) in {@code
 * docs/requirements/requirements.md}, both unanswered. Modelling them now would encode guessed
 * policy in the one place that must be trustworthy.
 *
 * <p>What is here is only what holds in any jurisdiction and any tenancy model: a person has an
 * identity, and a name that is not blank. See ADR-004 for why this context exists at all before its
 * requirements do.
 */
public final class Employee {

  private final EmployeeId id;
  private final String name;

  /**
   * @param id the employee's identity, never {@code null}
   * @param name the employee's name, never {@code null} and never blank
   * @throws InvalidEmployeeNameException if {@code name} is null or contains only whitespace
   */
  public Employee(EmployeeId id, String name) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    if (name == null || name.isBlank()) {
      throw new InvalidEmployeeNameException();
    }
    this.name = name;
  }

  public EmployeeId id() {
    return id;
  }

  public String name() {
    return name;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Employee employee && id.equals(employee.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "Employee[id=%s]".formatted(id);
  }
}
