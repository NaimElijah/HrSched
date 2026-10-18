package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.workforce.domain.model.PreferenceStrength;
import java.util.Objects;

/**
 * @param organisation whose employee
 * @param employee who stated it
 * @param period the span exactly as it was stated
 * @param strength how they said they felt about it; with the span, what names it (ADR-040)
 */
public record WithdrawPreferenceCommand(
    OrganisationId organisation,
    EmployeeId employee,
    TimeRange period,
    PreferenceStrength strength) {

  public WithdrawPreferenceCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
    Objects.requireNonNull(period, "period must not be null");
    Objects.requireNonNull(strength, "strength must not be null");
  }
}
