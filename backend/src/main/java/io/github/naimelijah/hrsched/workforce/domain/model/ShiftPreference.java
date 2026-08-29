package io.github.naimelijah.hrsched.workforce.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.Objects;

/**
 * A soft signal about when an employee would rather work.
 *
 * @param period the span the preference is about
 * @param strength how they feel about it
 */
public record ShiftPreference(TimeRange period, PreferenceStrength strength) {

  public ShiftPreference {
    Objects.requireNonNull(period, "period must not be null");
    Objects.requireNonNull(strength, "strength must not be null");
  }
}
