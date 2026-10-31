package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.InvalidTimeRangeException;
import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;

/**
 * The body of {@code POST /api/employees/{id}/unavailability}: a span as a caller sends it. Either
 * end may arrive absent, and {@code toPeriod} refuses that.
 *
 * @param start when the span begins
 * @param end when the span ends
 */
public record PeriodRequest(Instant start, Instant end) {

  /**
   * @return the span this request describes
   * @throws MalformedRequestException if either instant is absent, which Jackson maps to null
   *     rather than rejecting, or the span is one the store cannot hold (see {@link
   *     StorableSpans#toPeriod})
   * @throws InvalidTimeRangeException if the span ends at or before it starts
   */
  TimeRange toPeriod() {
    if (start == null || end == null) {
      throw new MalformedRequestException("Both start and end are required");
    }
    return StorableSpans.toPeriod(start, end);
  }
}
