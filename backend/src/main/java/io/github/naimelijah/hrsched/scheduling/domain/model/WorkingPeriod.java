package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.ZoneId;
import java.util.Objects;

/**
 * When a piece of work runs, and the zone its calendar days are counted in (R36).
 *
 * <p>The zone is the shift's, not the organisation's or the employee's: one organisation may run
 * sites in several zones, and a day there is the day its clocks show. {@link TimeRange} stays
 * zone-free because the shared kernel serves contexts that have no use for one (ADR-034).
 *
 * @param period when it runs
 * @param zone where its days begin and end
 */
public record WorkingPeriod(TimeRange period, ZoneId zone) {

  public WorkingPeriod {
    Objects.requireNonNull(period, "period must not be null");
    Objects.requireNonNull(zone, "zone must not be null");
  }
}
