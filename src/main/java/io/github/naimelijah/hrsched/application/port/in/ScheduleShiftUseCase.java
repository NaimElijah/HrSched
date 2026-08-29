package io.github.naimelijah.hrsched.application.port.in;

import io.github.naimelijah.hrsched.domain.model.InvalidShiftPeriodException;
import io.github.naimelijah.hrsched.domain.model.ShiftId;

/** Driving port: places a new shift on the schedule. */
public interface ScheduleShiftUseCase {

  /**
   * Records a new shift.
   *
   * @param command the shift to place, never {@code null}
   * @return the identity assigned to the new shift
   * @throws InvalidShiftPeriodException if the requested period does not end after it starts
   */
  ShiftId schedule(ScheduleShiftCommand command);
}
