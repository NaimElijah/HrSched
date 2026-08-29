package io.github.naimelijah.hrsched.workforce.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import java.util.Objects;

/**
 * A person who can be rostered.
 *
 * <p>An entity, not a value object: two employees who happen to share a name are still different
 * people, so equality is by {@link EmployeeId} alone. This is why it is a class and not a record.
 *
 * <p>STILL ALMOST EMPTY. Q2, Q3 and Q4 are now answered — see {@code
 * docs/requirements/requirements.md} — which confirms employees are real and unblocks the rest, but
 * none of it is modelled yet. An employee will carry the organisation it belongs to (the product is
 * multi-tenant), its qualifications, its availability, and its contracted hours.
 *
 * <p>What is here is only what holds regardless of configuration: a person has an identity and a
 * name that is not blank. Contracted-hours and rest thresholds are NOT fields on this class; they
 * are values in a configured constraint set. See ADR-006 for why this context was created before
 * its requirements existed.
 */
public final class Employee {

  private final EmployeeId id;
  private final String name;

  /**
   * @param id the employee's identity, never {@code null}
   * @param name the employee's name, never {@code null} and never blank
   * @throws NullPointerException if {@code id} or {@code name} is null
   * @throws InvalidEmployeeNameException if {@code name} contains only whitespace
   */
  public Employee(EmployeeId id, String name) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
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
