package io.github.naimelijah.hrsched.application.port.in;

import io.github.naimelijah.hrsched.domain.model.InvalidShiftPeriodException;
import io.github.naimelijah.hrsched.domain.model.Shift;

/** Driving port: places a new shift on the schedule. */
public interface ScheduleShiftUseCase {

  /**
   * Records a new shift.
   *
   * <p>Returns the shift itself rather than only its identity, so a caller that needs to represent
   * the result does not have to read it back through a second port.
   *
   * @param command the shift to place, never {@code null}
   * @return the newly created shift
   * @throws InvalidShiftPeriodException if the requested period does not end after it starts
   */
  Shift schedule(ScheduleShiftCommand command);
}
