package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import java.util.Optional;

/** Driving port: reads a single shift back. */
public interface FindShiftUseCase {

  /**
   * @param id the shift to look up, never {@code null}
   * @return the shift, or {@link Optional#empty()} if no shift has that identity
   */
  Optional<Shift> findById(ShiftId id);
}
