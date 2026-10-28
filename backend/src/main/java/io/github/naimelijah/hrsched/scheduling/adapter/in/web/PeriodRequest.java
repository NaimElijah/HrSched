package io.github.naimelijah.hrsched.scheduling.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;

/**
 * @param start when the span begins
 * @param end when the span ends
 */
public record PeriodRequest(Instant start, Instant end) {

  /**
   * @return the span this request describes
   * @throws MalformedRequestException if either instant is absent, which Jackson maps to null
   *     rather than rejecting, or the span is one the store cannot hold or generation cannot count
   *     (see {@link StorableSpans#toPeriod})
   */
  TimeRange toPeriod() {
    if (start == null || end == null) {
      throw new MalformedRequestException("Both start and end are required");
    }
    return StorableSpans.toPeriod(start, end);
  }
}
