package io.github.naimelijah.hrsched.workforce.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.workforce.domain.model.Employee;
import org.junit.jupiter.api.Test;

/**
 * The tenant predicate and the ordering this adapter promises.
 *
 * <p>The port deliberately has no {@code findById(EmployeeId)} overload, so the only way to read an
 * employee is to say whose employee you think they are. These tests are what proves the adapter
 * honours that rather than merely accepting the argument.
 */
class InMemoryEmployeeRepositoryTest {

  private static final OrganisationId ACME = OrganisationId.newId();
  private static final OrganisationId RIVAL = OrganisationId.newId();

  private final InMemoryEmployeeRepository repository = new InMemoryEmployeeRepository();

  private static Employee employee(OrganisationId organisation, String name) {
    return new Employee(EmployeeId.newId(), organisation, name);
  }

  @Test
  void GivenAnEmployee_WhenReadBackByTheirOwnOrganisation_ThenTheyAreFound() {
    Employee ada = employee(ACME, "Ada");
    repository.save(ada);

    assertThat(repository.findById(ACME, ada.id())).contains(ada);
  }

  @Test
  void GivenAnEmployeeOfAnotherOrganisation_WhenReadBackByUs_ThenTheyAreNotFound() {
    Employee theirs = employee(RIVAL, "Their Employee");
    repository.save(theirs);

    assertThat(repository.findById(ACME, theirs.id())).isEmpty();
  }

  @Test
  void GivenNoSuchEmployee_WhenReadingThem_ThenNothingIsFound() {
    assertThat(repository.findById(ACME, EmployeeId.newId())).isEmpty();
  }

  @Test
  void GivenTwoOrganisations_WhenListingOne_ThenOnlyItsOwnPeopleComeBack() {
    repository.save(employee(ACME, "Ada"));
    repository.save(employee(RIVAL, "Their Employee"));

    assertThat(repository.findByOrganisation(ACME))
        .extracting(Employee::name)
        .containsExactly("Ada");
  }

  @Test
  void GivenSeveralEmployees_WhenListingThem_ThenTheyComeBackInNameOrder() {
    repository.save(employee(ACME, "Grace"));
    repository.save(employee(ACME, "Ada"));
    repository.save(employee(ACME, "Marie"));

    assertThat(repository.findByOrganisation(ACME))
        .extracting(Employee::name)
        .containsExactly("Ada", "Grace", "Marie");
  }

  @Test
  void GivenTheSameEmployeeSavedTwice_WhenReadBack_ThenTheLaterVersionWins() {
    Employee original = employee(ACME, "Ada");
    Employee renamed = new Employee(original.id(), ACME, "Ada Lovelace");
    repository.save(original);
    repository.save(renamed);

    assertThat(repository.findByOrganisation(ACME))
        .extracting(Employee::name)
        .containsExactly("Ada Lovelace");
  }

  @Test
  void GivenAnEmptyStore_WhenListing_ThenTheListIsEmptyRatherThanNull() {
    assertThat(repository.findByOrganisation(ACME)).isNotNull().isEmpty();
  }
}
