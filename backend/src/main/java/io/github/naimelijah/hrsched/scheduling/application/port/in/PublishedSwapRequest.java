package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestId;
import io.github.naimelijah.hrsched.scheduling.domain.model.SwapRequestStatus;
import io.github.naimelijah.hrsched.shared.domain.model.EmployeeId;
import java.util.Objects;

/**
 * What a caller is told about a swap request.
 *
 * @param id which request
 * @param roster the roster it was raised against
 * @param shift the shift being handed over
 * @param requester whoever asked
 * @param counterparty whoever was asked
 * @param status where it has got to
 */
public record PublishedSwapRequest(
    SwapRequestId id,
    RosterId roster,
    ShiftId shift,
    EmployeeId requester,
    EmployeeId counterparty,
    SwapRequestStatus status) {

  public PublishedSwapRequest {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(roster, "roster must not be null");
    Objects.requireNonNull(shift, "shift must not be null");
    Objects.requireNonNull(requester, "requester must not be null");
    Objects.requireNonNull(counterparty, "counterparty must not be null");
    Objects.requireNonNull(status, "status must not be null");
  }
}
