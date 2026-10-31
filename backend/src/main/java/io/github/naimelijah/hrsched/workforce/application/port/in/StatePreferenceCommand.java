package io.github.naimelijah.hrsched.workforce.application.port.in;

import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.workforce.domain.model.PreferenceStrength;
import java.util.Objects;

/**
 * A request to record a soft preference about when an employee would rather work.
 *
 * @param organisation whose employee
 * @param employee whose preference
 * @param period the span it is about
 * @param strength how they feel about it
 */
public record StatePreferenceCommand(
    OrganisationId organisation,
    EmployeeId employee,
    TimeRange period,
    PreferenceStrength strength) {

  public StatePreferenceCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
    Objects.requireNonNull(period, "period must not be null");
    Objects.requireNonNull(strength, "strength must not be null");
  }
}
