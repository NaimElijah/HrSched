package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import io.github.naimelijah.hrsched.workforce.domain.model.PreferenceStrength;
import java.time.Instant;

/**
 * @param start when the span begins
 * @param end when the span ends
 * @param strength how the employee feels about it
 */
public record PreferenceRequest(Instant start, Instant end, PreferenceStrength strength) {

  /**
   * @return the span this preference is about
   * @throws MalformedRequestException if either instant is absent, which Jackson maps to null
   *     rather than rejecting, or the span is one the store cannot hold (see {@link
   *     StorableSpans#toPeriod})
   */
  TimeRange toPeriod() {
    if (start == null || end == null) {
      throw new MalformedRequestException("Both start and end are required");
    }
    return StorableSpans.toPeriod(start, end);
  }

  /**
   * @return how the employee feels about the span
   * @throws MalformedRequestException if no strength was given
   */
  PreferenceStrength requiredStrength() {
    if (strength == null) {
      throw new MalformedRequestException("strength is required");
    }
    return strength;
  }
}
