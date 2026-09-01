package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.application.port.in.PublishedSwapRequest;
import java.util.UUID;

/**
 * @param id which request
 * @param roster the roster it was raised against
 * @param shift the shift being handed over
 * @param requester whoever asked
 * @param counterparty whoever was asked
 * @param status where it has got to
 */
public record SwapRequestResponse(
    UUID id, UUID roster, UUID shift, UUID requester, UUID counterparty, String status) {

  static SwapRequestResponse from(PublishedSwapRequest request) {
    return new SwapRequestResponse(
        request.id().value(),
        request.roster().value(),
        request.shift().value(),
        request.requester().value(),
        request.counterparty().value(),
        request.status().name());
  }
}
