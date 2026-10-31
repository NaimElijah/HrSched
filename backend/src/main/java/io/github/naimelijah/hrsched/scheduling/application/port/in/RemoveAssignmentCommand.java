package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Objects;

/**
 * A request to take one person off one shift of a draft.
 *
 * @param organisation whose roster is being edited
 * @param roster which draft
 * @param shift which shift the person is coming off
 * @param employee who is coming off it
 */
public record RemoveAssignmentCommand(
    OrganisationId organisation, RosterId roster, ShiftId shift, EmployeeId employee) {

  public RemoveAssignmentCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(roster, "roster must not be null");
    Objects.requireNonNull(shift, "shift must not be null");
    Objects.requireNonNull(employee, "employee must not be null");
  }
}
