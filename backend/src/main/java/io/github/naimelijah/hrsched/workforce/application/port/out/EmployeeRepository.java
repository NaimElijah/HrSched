package io.github.naimelijah.hrsched.workforce.application.port.out;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.workforce.domain.model.Employee;
import java.util.List;
import java.util.Optional;

/**
 * Driven port: storing employees.
 *
 * <p>Every lookup takes an {@link OrganisationId}. There is deliberately no {@code findById} that
 * takes an employee identity alone: making the tenant scope part of the signature means a
 * cross-tenant read cannot be written by forgetting a filter, only by explicitly passing the wrong
 * organisation.
 */
public interface EmployeeRepository {

  /**
   * @param employee the employee to store, replacing any with the same identity
   */
  void save(Employee employee);

  /**
   * @param organisation the organisation the employee must belong to
   * @param id who to look up
   * @return the employee, or empty if that organisation has no such employee
   */
  Optional<Employee> findById(OrganisationId organisation, EmployeeId id);

  /**
   * @param organisation whose employees to list
   * @return every employee of that organisation
   */
  List<Employee> findByOrganisation(OrganisationId organisation);
}
