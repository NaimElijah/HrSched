package io.github.naimelijah.hrsched.workforce.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A person who can be rostered: the aggregate root of the workforce context.
 *
 * <p>Holds everything that decides whether this person may work a given span — the qualifications
 * they hold, the times they cannot work, and what they are contracted to do. Unavailability and
 * preferences are part of this aggregate rather than separate entities because they have no life of
 * their own: they exist only in relation to one employee, and are only ever read alongside them.
 *
 * <p>Immutable. Every change returns a new instance, so an employee can never be observed
 * half-updated. Every instance passes through one private constructor, which checks the name; R7,
 * that declared spans never overlap, is checked by {@link #unavailableFor}, the only way to add
 * one. The name is checked again on every change; R7 again on every rebuild from storage, which
 * re-adds each stored span through that method.
 *
 * <p>Belongs to exactly one {@link OrganisationId}, but cannot tell whose a {@link QualificationId}
 * is, so nothing in this class stops it holding another organisation's. The application layer does:
 * it grants a qualification only once it has found it in the employee's own organisation.
 */
public final class Employee {

  private final EmployeeId id;
  private final OrganisationId organisation;
  private final String name;
  private final Set<QualificationId> qualifications;
  private final List<TimeRange> unavailability;
  private final List<ShiftPreference> preferences;
  private final ContractTerms contractTerms;

  /**
   * Creates an employee with no qualifications, no declared unavailability, and no contract terms.
   *
   * @param id the employee's identity, never {@code null}
   * @param organisation the organisation they belong to, never {@code null}
   * @param name their name, never {@code null} and never blank
   * @throws NullPointerException if any argument is {@code null}
   * @throws InvalidEmployeeNameException if {@code name} is blank
   */
  public Employee(EmployeeId id, OrganisationId organisation, String name) {
    this(id, organisation, name, Set.of(), List.of(), List.of(), null);
  }

  private Employee(
      EmployeeId id,
      OrganisationId organisation,
      String name,
      Collection<QualificationId> qualifications,
      List<TimeRange> unavailability,
      List<ShiftPreference> preferences,
      ContractTerms contractTerms) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.organisation = Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(name, "name must not be null");
    if (name.isBlank()) {
      throw new InvalidEmployeeNameException();
    }
    this.name = name;
    this.qualifications = Set.copyOf(qualifications);
    this.unavailability = List.copyOf(unavailability);
    this.preferences = List.copyOf(preferences);
    this.contractTerms = contractTerms;
  }

  /**
   * Adds a qualification to those this employee holds.
   *
   * @param qualification a qualification this employee holds
   * @return an employee holding it; holding it already is not an error
   */
  public Employee holding(QualificationId qualification) {
    Objects.requireNonNull(qualification, "qualification must not be null");
    Set<QualificationId> held = new HashSet<>(qualifications);
    held.add(qualification);
    return new Employee(id, organisation, name, held, unavailability, preferences, contractTerms);
  }

  /**
   * Declares a span this employee cannot work.
   *
   * @param period the span they are unavailable for
   * @return an employee unavailable for that span
   * @throws OverlappingUnavailabilityException if it overlaps a span already declared
   */
  public Employee unavailableFor(TimeRange period) {
    Objects.requireNonNull(period, "period must not be null");
    if (unavailability.stream().anyMatch(period::overlaps)) {
      throw new OverlappingUnavailabilityException();
    }
    List<TimeRange> declared = new ArrayList<>(unavailability);
    declared.add(period);
    return new Employee(
        id, organisation, name, qualifications, declared, preferences, contractTerms);
  }

  /**
   * Records a soft preference about when this employee would rather work.
   *
   * <p>Never refused: preferences may repeat one another, and may overlap unavailability or each
   * other, because none of them blocks anything.
   *
   * @param preference a soft signal about when they would rather work
   * @return an employee carrying that preference, after any they already stated
   */
  public Employee preferring(ShiftPreference preference) {
    Objects.requireNonNull(preference, "preference must not be null");
    List<ShiftPreference> stated = new ArrayList<>(preferences);
    stated.add(preference);
    return new Employee(
        id, organisation, name, qualifications, unavailability, stated, contractTerms);
  }

  /**
   * Withdraws a span this employee declared they cannot work.
   *
   * <p>The span is named exactly as it was declared. Declared spans never overlap, so it names at
   * most one, and a span that only overlaps one is not it: a withdrawal removes a declaration
   * rather than carving a piece out of it (ADR-040).
   *
   * @param period the span exactly as it was declared
   * @return an employee no longer unavailable for it
   * @throws UnknownDeclarationException if no declared span is exactly that one
   */
  public Employee noLongerUnavailableFor(TimeRange period) {
    Objects.requireNonNull(period, "period must not be null");
    List<TimeRange> remaining = new ArrayList<>(unavailability);
    if (!remaining.remove(period)) {
      throw new UnknownDeclarationException();
    }
    return new Employee(
        id, organisation, name, qualifications, remaining, preferences, contractTerms);
  }

  /**
   * Withdraws a soft preference this employee stated.
   *
   * <p>Preferences may repeat one another exactly, and identical statements cannot be told apart,
   * so every statement equal to this one is withdrawn together (ADR-040).
   *
   * @param preference the span and strength exactly as they were stated
   * @return an employee no longer carrying that preference
   * @throws UnknownDeclarationException if no stated preference is exactly that one
   */
  public Employee noLongerPreferring(ShiftPreference preference) {
    Objects.requireNonNull(preference, "preference must not be null");
    List<ShiftPreference> remaining = new ArrayList<>(preferences);
    if (!remaining.removeIf(preference::equals)) {
      throw new UnknownDeclarationException();
    }
    return new Employee(
        id, organisation, name, qualifications, unavailability, remaining, contractTerms);
  }

  /**
   * Sets what this employee is contracted to work.
   *
   * @param terms what this employee is contracted to work
   * @return an employee on those terms, replacing any previous ones
   */
  public Employee contractedTo(ContractTerms terms) {
    Objects.requireNonNull(terms, "terms must not be null");
    return new Employee(id, organisation, name, qualifications, unavailability, preferences, terms);
  }

  public EmployeeId id() {
    return id;
  }

  public OrganisationId organisation() {
    return organisation;
  }

  public String name() {
    return name;
  }

  /**
   * @return the qualifications held, as an unmodifiable set with no order
   */
  public Set<QualificationId> qualifications() {
    return qualifications;
  }

  /**
   * @return the spans declared unavailable, unmodifiable and in the order they were declared; none
   *     overlaps another (R7)
   */
  public List<TimeRange> unavailability() {
    return unavailability;
  }

  /**
   * @return the preferences stated, unmodifiable and in the order they were stated; the same one
   *     may appear more than once
   */
  public List<ShiftPreference> preferences() {
    return preferences;
  }

  /**
   * @return the contract terms, or empty for someone with no contracted hours such as casual staff
   */
  public Optional<ContractTerms> contractTerms() {
    return Optional.ofNullable(contractTerms);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Employee employee && id.equals(employee.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  // The name is left out on purpose: it is personal data, and a toString travels into logs and
  // exception messages. Identities only.
  @Override
  public String toString() {
    return "Employee[id=%s, organisation=%s]".formatted(id, organisation);
  }
}
