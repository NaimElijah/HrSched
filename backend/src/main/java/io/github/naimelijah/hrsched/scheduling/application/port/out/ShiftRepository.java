package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import java.util.Optional;

/**
 * Driven port: stores and retrieves shifts.
 *
 * <p>Declared here, in the application layer, and implemented outward in an adapter. That inversion
 * is the whole point of the hexagon — the application states what it needs, and storage technology
 * conforms to it, never the reverse.
 *
 * <p>The interface is deliberately narrow. It will grow as use cases need it, not in anticipation
 * of them.
 */
public interface ShiftRepository {

  /**
   * Persists a shift, replacing any existing shift with the same identity.
   *
   * @param shift the shift to store, never {@code null}
   */
  void save(Shift shift);

  /**
   * @param id the identity to look up, never {@code null}
   * @return the stored shift, or {@link Optional#empty()} if there is none
   */
  Optional<Shift> findById(ShiftId id);
}
