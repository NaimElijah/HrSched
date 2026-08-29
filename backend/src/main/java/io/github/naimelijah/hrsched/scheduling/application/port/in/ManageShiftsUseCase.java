package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;

/** Driving port: stating what work needs covering. */
public interface ManageShiftsUseCase {

  /**
   * @param actor whoever is defining it; must be a Scheduler or Admin of the organisation
   * @param command the shift to define
   * @return the new shift identity
   */
  ShiftId defineShift(Actor actor, DefineShiftCommand command);
}
