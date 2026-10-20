package io.github.naimelijah.hrsched.scheduling.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.naimelijah.hrsched.shared.domain.model.TimeRange;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What somebody said about when they would like to work, as the preference objective reads it. */
class StatedPreferencesTest {

  private static final TimeRange MONDAY =
      new TimeRange(Instant.parse("2026-09-07T08:00:00Z"), Instant.parse("2026-09-07T16:00:00Z"));
  private static final TimeRange MONDAY_LATE =
      new TimeRange(Instant.parse("2026-09-07T12:00:00Z"), Instant.parse("2026-09-07T20:00:00Z"));

  /** R26 lets the same wish be stated twice; it is still one wish, so it is weighed once. */
  @Test
  void GivenTheSameSpanStatedTwice_WhenHeld_ThenItIsHeldOnce() {
    StatedPreferences preferences =
        new StatedPreferences(List.of(MONDAY, MONDAY), List.of(MONDAY, MONDAY));

    assertThat(preferences.wouldLikeToWork()).containsExactly(MONDAY);
    assertThat(preferences.wouldRatherNotWork()).containsExactly(MONDAY);
  }

  /** Overlapping is not identical: two different spans are two statements, and both are kept. */
  @Test
  void GivenOverlappingButDifferentSpans_WhenHeld_ThenBothAreKept() {
    StatedPreferences preferences = new StatedPreferences(List.of(MONDAY, MONDAY_LATE), List.of());

    assertThat(preferences.wouldLikeToWork()).containsExactly(MONDAY, MONDAY_LATE);
  }

  @Test
  void GivenNothingStated_WhenAskedForNone_ThenBothListsAreEmpty() {
    assertThat(StatedPreferences.none().wouldLikeToWork()).isEmpty();
    assertThat(StatedPreferences.none().wouldRatherNotWork()).isEmpty();
  }

  @Test
  void GivenAListChangedAfterwards_WhenHeld_ThenThePreferencesDoNotChange() {
    List<TimeRange> stated = new ArrayList<>(List.of(MONDAY));
    StatedPreferences preferences = new StatedPreferences(stated, List.of());

    stated.add(MONDAY_LATE);

    assertThat(preferences.wouldLikeToWork()).containsExactly(MONDAY);
  }

  @Test
  void GivenANullList_WhenHeld_ThenItIsRejected() {
    assertThatThrownBy(() -> new StatedPreferences(null, List.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new StatedPreferences(List.of(), null))
        .isInstanceOf(NullPointerException.class);
  }
}
