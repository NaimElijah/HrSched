package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.scheduling.application.port.in.RosteredShift;
import java.time.Instant;
import java.util.UUID;

/**
 * One piece of work the caller is rostered for.
 *
 * <p>Carries no employee, because every entry is the caller's own — repeating their identity on
 * each row would only invite a client to display somebody else's.
 *
 * @param shift which shift
 * @param start when it begins
 * @param end when it ends
 * @param roster the published roster that says so
 */
public record RosteredShiftResponse(UUID shift, Instant start, Instant end, UUID roster) {

  static RosteredShiftResponse from(RosteredShift rostered) {
    return new RosteredShiftResponse(
        rostered.shift().value(),
        rostered.period().start(),
        rostered.period().end(),
        rostered.roster().value());
  }
}
