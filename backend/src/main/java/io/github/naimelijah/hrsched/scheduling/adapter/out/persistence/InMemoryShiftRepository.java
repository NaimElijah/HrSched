package io.github.naimelijah.hrsched.scheduling.adapter.out.persistence;

import io.github.naimelijah.hrsched.scheduling.application.port.out.ShiftRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * Driven adapter storing shifts in memory.
 *
 * <p>PLACEHOLDER until ADR-004. Nothing survives a restart. Every read filters by organisation
 * first, mirroring the tenant predicate a real store will need.
 */
@Repository
public class InMemoryShiftRepository implements ShiftRepository {

  private final Map<ShiftId, Shift> byId = new ConcurrentHashMap<>();

  @Override
  public void save(Shift shift) {
    byId.put(shift.id(), shift);
  }

  @Override
  public Optional<Shift> findById(OrganisationId organisation, ShiftId id) {
    return Optional.ofNullable(byId.get(id))
        .filter(shift -> shift.organisation().equals(organisation));
  }

  @Override
  public List<Shift> findWithin(OrganisationId organisation, TimeRange within) {
    return byId.values().stream()
        .filter(shift -> shift.organisation().equals(organisation))
        .filter(shift -> shift.period().overlaps(within))
        .sorted(Comparator.comparing(shift -> shift.period().start()))
        .toList();
  }
}
