package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.Actor;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.List;

/** Driving port: stating what work needs covering. */
public interface ManageShiftsUseCase {

  /**
   * @param actor whoever is defining it; must be a Scheduler of the organisation
   * @param command the shift to define
   * @return the new shift identity
   */
  ShiftId defineShift(Actor actor, DefineShiftCommand command);

  /**
   * Lists the shifts overlapping a span, so that a caller can see what needs covering before asking
   * for a roster.
   *
   * <p>A span is required rather than defaulted: there is no honest default window, and inventing
   * one would decide for the caller how much of the future they meant.
   *
   * @param actor whoever is asking; any member of the organisation
   * @param organisation whose shifts
   * @param within the span to look inside
   * @return every shift of that organisation overlapping the span
   */
  List<Shift> findShifts(Actor actor, OrganisationId organisation, TimeRange within);
}
