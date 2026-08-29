package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.time.Duration;
import java.util.Objects;

/**
 * @param organisation whose employee
 * @param employee whose terms these are
 * @param weeklyContractedHours what they are contracted to work each week, strictly positive
 */
public record SetContractTermsCommand(
    OrganisationId organisation, EmployeeId employee, Duration weeklyContractedHours) {

  public SetContractTermsCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
    Objects.requireNonNull(weeklyContractedHours, "weeklyContractedHours must not be null");
  }
}
