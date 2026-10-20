package io.github.naimelijah.hrsched.scheduling.domain.model;

import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * What one person said about when they would like to work and when they would rather not, as the
 * preference objective reads it (ADR-010).
 *
 * <p>Soft, always: nothing here blocks an assignment. Unavailability is what blocks, and a
 * candidate carries it separately so that the two can never be confused.
 *
 * <p>Saying the same thing twice says it once, so each list holds a span once however often it was
 * stated: R26 lets an identical statement be made twice, and counting both would weigh one wish
 * double.
 *
 * @param wouldLikeToWork spans they would like to work, each held once
 * @param wouldRatherNotWork spans they would rather not work, but can, each held once
 */
public record StatedPreferences(
    List<TimeRange> wouldLikeToWork, List<TimeRange> wouldRatherNotWork) {

  public StatedPreferences {
    wouldLikeToWork = List.copyOf(new LinkedHashSet<>(wouldLikeToWork));
    wouldRatherNotWork = List.copyOf(new LinkedHashSet<>(wouldRatherNotWork));
  }

  /**
   * @return the preferences of somebody who stated none
   */
  public static StatedPreferences none() {
    return new StatedPreferences(List.of(), List.of());
  }
}
