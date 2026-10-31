package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.Objects;

/**
 * A request to record a span an employee cannot work.
 *
 * @param organisation whose employee
 * @param employee who is unavailable
 * @param period when they cannot work
 */
public record DeclareUnavailabilityCommand(
    OrganisationId organisation, EmployeeId employee, TimeRange period) {

  /**
   * @throws NullPointerException if any component is {@code null}
   */
  public DeclareUnavailabilityCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
    Objects.requireNonNull(period, "period must not be null");
  }
}
