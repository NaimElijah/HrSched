package io.github.naimelijah.hrsched.workforce.adapter.in.web;

import io.github.naimelijah.hrsched.workforce.domain.model.PreferenceStrength;
import io.github.naimelijah.hrsched.workforce.domain.model.ShiftPreference;
import java.time.Instant;

/**
 * A stated preference, as the API renders one.
 *
 * @param start when the span begins
 * @param end when the span ends
 * @param strength how the employee feels about it
 */
public record PreferenceResponse(Instant start, Instant end, PreferenceStrength strength) {

  static PreferenceResponse from(ShiftPreference preference) {
    return new PreferenceResponse(
        preference.period().start(), preference.period().end(), preference.strength());
  }
}
