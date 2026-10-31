package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Objects;

/**
 * A request to put one person on one shift of a draft.
 *
 * @param organisation whose roster is being edited
 * @param roster which draft
 * @param shift which shift to cover
 * @param employee who covers it
 */
public record AssignToShiftCommand(
    OrganisationId organisation, RosterId roster, ShiftId shift, EmployeeId employee) {

  public AssignToShiftCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(roster, "roster must not be null");
    Objects.requireNonNull(shift, "shift must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
  }
}
