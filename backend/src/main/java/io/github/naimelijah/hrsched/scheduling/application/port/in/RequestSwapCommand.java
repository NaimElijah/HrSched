package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import java.util.Objects;

/**
 * A request to hand one assignment of a published roster to a colleague.
 *
 * @param organisation whose roster
 * @param roster the published roster the shift is on
 * @param shift the shift being handed over
 * @param requester whoever currently covers it
 * @param counterparty whoever is being asked to take it
 */
public record RequestSwapCommand(
    OrganisationId organisation,
    RosterId roster,
    ShiftId shift,
    EmployeeId requester,
    EmployeeId counterparty) {

  public RequestSwapCommand {
    Objects.requireNonNull(organisation, "organisation must not be null");
    Objects.requireNonNull(roster, "roster must not be null");
    Objects.requireNonNull(shift, "shift must not be null");
    Objects.requireNonNull(requester, "requester must not be null");
    Objects.requireNonNull(counterparty, "counterparty must not be null");
  }
}
