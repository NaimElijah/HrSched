package io.github.naimelijah.hrsched.access.adapter.out.workforce;

import io.github.naimelijah.hrsched.access.application.port.out.EmployeeDirectory;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.workforce.application.port.in.ManageEmployeesUseCase;
import org.springframework.stereotype.Component;

/**
 * Anti-corruption layer between access and workforce.
 *
 * <p>THE ONE CLASS IN ACCESS THAT NAMES ANOTHER CONTEXT, and it may name only the published driving
 * port, never the workforce domain. The architecture rules let only a class in a context's {@code
 * adapter.out} name another context, and only that port (ADR-008); this adapter is where access
 * spends the allowance, deliberately, in one place.
 *
 * <p>It narrows an answer rather than translating one. Workforce publishes the employee it finds,
 * name included, because its own screens need that; access needs only whether there is one, so the
 * record is reduced to a boolean here and never leaves this method. That is the whole reason the
 * driven port asks a yes-or-no question: a colleague's name has no business in the context that
 * writes the access audit trail.
 */
@Component
public class WorkforceEmployeeDirectory implements EmployeeDirectory {

  private final ManageEmployeesUseCase employees;

  /**
   * @param employees the workforce context's use case that looks an employee up
   */
  public WorkforceEmployeeDirectory(ManageEmployeesUseCase employees) {
    this.employees = employees;
  }

  @Override
  public boolean isEmployedHere(Actor actor, OrganisationId organisation, EmployeeId employee) {
    return employees.findEmployee(actor, organisation, employee).isPresent();
  }
}
