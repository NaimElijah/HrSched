package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import java.time.Instant;
import java.util.UUID;

/**
 * HTTP representation of a shift.
 *
 * @param id the shift identity
 * @param start ISO-8601 instant the shift begins
 * @param end ISO-8601 instant the shift ends
 */
public record ShiftResponse(UUID id, Instant start, Instant end) {

  /**
   * Flattens a domain shift into its wire form, so that domain types never reach a client.
   *
   * @param shift the shift to represent, never {@code null}
   * @return the response body
   */
  static ShiftResponse from(Shift shift) {
    return new ShiftResponse(shift.id().value(), shift.period().start(), shift.period().end());
  }
}
