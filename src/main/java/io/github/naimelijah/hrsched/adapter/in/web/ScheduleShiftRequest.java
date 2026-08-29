package io.github.naimelijah.hrsched.adapter.in.web;

import java.time.Instant;

/**
 * HTTP body for scheduling a shift.
 *
 * <p>A transport type, deliberately separate from {@code ScheduleShiftCommand}: the wire format is
 * allowed to change on its own schedule without dragging the application layer with it.
 *
 * @param start ISO-8601 instant the shift begins
 * @param end ISO-8601 instant the shift ends
 */
public record ScheduleShiftRequest(Instant start, Instant end) {}
