package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.time.Duration;
import java.util.Objects;

/**
 * A request to set what an employee is contracted to work, replacing any previous terms.
 *
 * @param organisation whose employee
 * @param employee whose terms these are
 * @param weeklyContractedHours what they are contracted to work each week, strictly positive
 */
public record SetContractTermsCommand(
    OrganisationId organisation, EmployeeId employee, Duration weeklyContractedHours) {

  /**
   * @throws NullPointerException if any component is {@code null}. Hours that are not positive
   *     pass: {@code ContractTerms} refuses them
   */
  public SetContractTermsCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
    Objects.requireNonNull(weeklyContractedHours, "weeklyContractedHours must not be null");
  }
}
