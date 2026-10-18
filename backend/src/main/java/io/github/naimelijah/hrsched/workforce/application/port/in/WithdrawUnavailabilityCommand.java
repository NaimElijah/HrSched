package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.Objects;

/**
 * @param organisation whose employee
 * @param employee who declared it
 * @param period the span exactly as it was declared, which is what names it (ADR-040)
 */
public record WithdrawUnavailabilityCommand(
    OrganisationId organisation, EmployeeId employee, TimeRange period) {

  public WithdrawUnavailabilityCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
    Objects.requireNonNull(period, "period must not be null");
  }
}
