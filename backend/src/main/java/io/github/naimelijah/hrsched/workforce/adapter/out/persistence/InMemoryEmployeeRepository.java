package io.github.naimelijah.hrsched.workforce.adapter.out.persistence;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.workforce.application.port.out.EmployeeRepository;
import io.github.naimelijah.hrsched.workforce.domain.model.Employee;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing employees in memory.
 *
 * <p>PLACEHOLDER until ADR-004. Nothing survives a restart.
 *
 * <p>Every read filters by organisation before returning anything, mirroring the {@code WHERE
 * organisation_id = ?} that a real store will need. Getting that wrong is a data leak between
 * customers, so it is written the same way here as it will be there.
 */
@Repository
public class InMemoryEmployeeRepository implements EmployeeRepository {

  private final Map<EmployeeId, Employee> employeesById = new ConcurrentHashMap<>();

  @Override
  public void save(Employee employee) {
    employeesById.put(employee.id(), employee);
  }

  @Override
  public Optional<Employee> findById(OrganisationId organisation, EmployeeId id) {
    return Optional.ofNullable(employeesById.get(id))
        .filter(employee -> employee.organisation().equals(organisation));
  }

  @Override
  public List<Employee> findByOrganisation(OrganisationId organisation) {
    return employeesById.values().stream()
        .filter(employee -> employee.organisation().equals(organisation))
        .sorted(Comparator.comparing(Employee::name))
        .toList();
  }
}
