package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import java.time.Instant;
import java.util.Objects;

/**
 * HTTP body for scheduling a shift.
 *
 * <p>A transport type, deliberately separate from {@code ScheduleShiftCommand}: the wire format is
 * allowed to change on its own schedule without dragging the application layer with it.
 *
 * <p>Rejects a body with missing fields at construction. Jackson surfaces that as a deserialization
 * failure, which the adapter maps to 400 — so an incomplete request never reaches the application
 * layer, and a caller error is never reported as a server error.
 *
 * @param start ISO-8601 instant the shift begins, required
 * @param end ISO-8601 instant the shift ends, required
 */
public record ScheduleShiftRequest(Instant start, Instant end) {

  public ScheduleShiftRequest {
    Objects.requireNonNull(start, "start is required");
    Objects.requireNonNull(end, "end is required");
  }
}
