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
 * <p>THE ONLY CLASS IN ACCESS PERMITTED TO NAME ANOTHER CONTEXT, and it may name only the published
 * driving port, never the workforce domain (ADR-008). The architecture rules enforce that; this
 * adapter is where the allowance is spent, deliberately, in one place.
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

  public WorkforceEmployeeDirectory(ManageEmployeesUseCase employees) {
    this.employees = employees;
  }

  @Override
  public boolean isEmployedHere(Actor actor, OrganisationId organisation, EmployeeId employee) {
    return employees.findEmployee(actor, organisation, employee).isPresent();
  }
}
