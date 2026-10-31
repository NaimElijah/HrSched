package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.QualificationId;
import java.util.Objects;

/**
 * A request to record that an employee holds a qualification.
 *
 * @param organisation whose employee and whose qualification
 * @param employee who holds it
 * @param qualification what they hold
 */
public record GrantQualificationCommand(
    OrganisationId organisation, EmployeeId employee, QualificationId qualification) {

  public GrantQualificationCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
    Objects.requireNonNull(qualification, "qualification must not be null");
  }
}
