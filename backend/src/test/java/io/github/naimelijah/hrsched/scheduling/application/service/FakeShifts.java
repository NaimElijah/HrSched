package io.github.naimelijah.hrsched.scheduling.application.service;

import io.github.naimelijah.hrsched.scheduling.application.port.out.ShiftRepository;
import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Hand-written, like every other double here: the repository is our own port. */
final class FakeShifts implements ShiftRepository {

  private final Map<ShiftId, Shift> stored = new LinkedHashMap<>();

  @Override
  public void save(Shift shift) {
    stored.put(shift.id(), shift);
  }

  @Override
  public Optional<Shift> findById(OrganisationId organisation, ShiftId id) {
    return Optional.ofNullable(stored.get(id))
        .filter(shift -> shift.organisation().equals(organisation));
  }

  @Override
  public List<Shift> findWithin(OrganisationId organisation, TimeRange within) {
    return stored.values().stream()
        .filter(shift -> shift.organisation().equals(organisation))
        .filter(shift -> shift.period().overlaps(within))
        .toList();
  }
}
