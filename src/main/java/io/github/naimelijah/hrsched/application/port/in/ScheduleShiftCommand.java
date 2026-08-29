package io.github.naimelijah.hrsched.application.port.in;

import java.time.Instant;
import java.util.Objects;

/**
 * Request to place a new shift on the schedule.
 *
 * <p>Carries only transport-independent data. Whether the caller arrived over HTTP, a CLI, or a
 * message queue is not visible here, which is what lets the use case be driven by any adapter.
 *
 * @param start when the shift should begin, never {@code null}
 * @param end when the shift should end, never {@code null}
 */
public record ScheduleShiftCommand(Instant start, Instant end) {

  public ScheduleShiftCommand {
    Objects.requireNonNull(start, "start must not be null");
    Objects.requireNonNull(end, "end must not be null");
  }
}
