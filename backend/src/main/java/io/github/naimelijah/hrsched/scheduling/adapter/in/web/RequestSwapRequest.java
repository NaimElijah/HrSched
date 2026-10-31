package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import java.util.UUID;

/**
 * The body of a request to hand one assignment of a published roster to a colleague.
 *
 * @param roster the published roster the shift is on
 * @param shift the shift being handed over
 * @param requester whoever currently covers it
 * @param counterparty whoever is being asked to take it
 */
public record RequestSwapRequest(UUID roster, UUID shift, UUID requester, UUID counterparty) {

  RosterId requiredRoster() {
    return new RosterId(required(roster, "roster"));
  }

  ShiftId requiredShift() {
    return new ShiftId(required(shift, "shift"));
  }

  EmployeeId requiredRequester() {
    return new EmployeeId(required(requester, "requester"));
  }

  EmployeeId requiredCounterparty() {
    return new EmployeeId(required(counterparty, "counterparty"));
  }

  /**
   * @throws MalformedRequestException if the field is absent, which Jackson maps to null rather
   *     than rejecting
   */
  private static UUID required(UUID value, String field) {
    if (value == null) {
      throw new MalformedRequestException(field + " is required");
    }
    return value;
  }
}
