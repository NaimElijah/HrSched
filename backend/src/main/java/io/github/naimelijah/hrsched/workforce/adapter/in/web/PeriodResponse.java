package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;

/**
 * A span of time, as the API renders one.
 *
 * @param start when it begins
 * @param end when it ends
 */
public record PeriodResponse(Instant start, Instant end) {

  static PeriodResponse from(TimeRange period) {
    return new PeriodResponse(period.start(), period.end());
  }
}
