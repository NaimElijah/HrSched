package io.github.naimelijah.hrsched.scheduling.application.port.in;

import io.github.naimelijah.hrsched.scheduling.domain.model.RosterId;
import io.github.naimelijah.hrsched.scheduling.domain.model.ShiftId;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.ZoneId;
import java.util.Objects;

/**
 * One piece of work somebody is rostered for.
 *
 * <p>Carries the shift's period as well as its identity, because an {@code Assignment} alone is
 * useless to whoever is reading it: knowing you are on shift {@code 8f3a…} answers nothing without
 * knowing when it runs.
 *
 * <p>Names the roster it came from so the answer can be traced to the version that is
 * authoritative, which matters once a roster has been superseded by another.
 *
 * @param shift which shift
 * @param period when it runs
 * @param zone the zone the shift's working-time days are counted in (R36)
 * @param roster the published roster that says so
 */
public record RosteredShift(ShiftId shift, TimeRange period, ZoneId zone, RosterId roster) {

  public RosteredShift {
    Objects.requireNonNull(shift, "shift must not be null");
    Objects.requireNonNull(period, "period must not be null");
    Objects.requireNonNull(zone, "zone must not be null");
    Objects.requireNonNull(roster, "roster must not be null");
  }
}
