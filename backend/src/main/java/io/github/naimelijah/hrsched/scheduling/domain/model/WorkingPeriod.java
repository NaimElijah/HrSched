package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

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

  /**
   * Splits this period at midnight in its own zone and gives the time falling on each calendar day.
   *
   * <p>A day is as long as its clocks make it: 23 hours when they go forward, 25 when they go back.
   * The end is exclusive, so a period finishing exactly at midnight puts nothing on the next day.
   *
   * @return time worked per calendar day, in date order, with no zero-length entries
   */
  public SortedMap<LocalDate, Duration> timePerDay() {
    SortedMap<LocalDate, Duration> perDay = new TreeMap<>();
    Instant cursor = period.start();
    while (cursor.isBefore(period.end())) {
      LocalDate day = LocalDate.ofInstant(cursor, zone);
      ZonedDateTime nextDay = day.plusDays(1).atStartOfDay(zone);
      // When the clocks go back across midnight, the next day starts twice and atStartOfDay
      // answers with the first, which can lie before the cursor. The day being counted runs
      // until the second.
      if (!nextDay.toInstant().isAfter(cursor)) {
        nextDay = nextDay.withLaterOffsetAtOverlap();
      }
      Instant midnight = nextDay.toInstant();
      Instant sliceEnd = midnight.isBefore(period.end()) ? midnight : period.end();
      perDay.merge(day, Duration.between(cursor, sliceEnd), Duration::plus);
      cursor = sliceEnd;
    }
    return Collections.unmodifiableSortedMap(perDay);
  }
}
