package io.github.naimelijah.hrsched.scheduling.application.port.out;

import io.github.naimelijah.hrsched.scheduling.domain.model.Shift;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.OrganisationId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.List;
import java.util.Optional;

/**
 * Driven port: storing shifts.
 *
 * <p>Every lookup is scoped by organisation, so a cross-tenant read cannot be written by forgetting
 * a filter.
 */
public interface ShiftRepository {

  void save(Shift shift);

  Optional<Shift> findById(OrganisationId organisation, ShiftId id);

  /**
   * @param organisation whose shifts
   * @param within the span to look inside
   * @return every shift of that organisation that overlaps the span
   */
  List<Shift> findWithin(OrganisationId organisation, TimeRange within);
}
