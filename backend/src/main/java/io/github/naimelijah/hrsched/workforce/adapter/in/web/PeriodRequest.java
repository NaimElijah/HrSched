package io.github.naimelijah.hrsched.workforce.adapter.in.web;

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
   *     rather than rejecting
   */
  TimeRange toPeriod() {
    if (start == null || end == null) {
      throw new MalformedRequestException("Both start and end are required");
    }
    return new TimeRange(start, end);
  }
}
