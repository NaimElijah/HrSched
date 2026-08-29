package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import io.github.naimelijah.hrsched.scheduling.application.port.out.ShiftRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing shifts in memory.
 *
 * <p>PLACEHOLDER. Nothing survives a restart. It exists so the hexagon is complete and provable
 * without choosing a database, because that choice is ADR-004 and is not yet made. Adding a JPA
 * starter here would have made that decision by default.
 *
 * <p>When ADR-004 lands, this class is replaced and nothing inward of the {@link ShiftRepository}
 * port changes — which is the property the architecture exists to buy.
 */
@Repository
public class InMemoryShiftRepository implements ShiftRepository {

  private final Map<ShiftId, Shift> shiftsById = new ConcurrentHashMap<>();

  @Override
  public void save(Shift shift) {
    shiftsById.put(shift.id(), shift);
  }

  @Override
  public Optional<Shift> findById(ShiftId id) {
    return Optional.ofNullable(shiftsById.get(id));
  }
}
