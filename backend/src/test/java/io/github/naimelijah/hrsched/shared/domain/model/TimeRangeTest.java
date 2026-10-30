package io.github.naimelijah.hrsched.shared.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Specification of the time-range invariant, shared by every context that spans time. */
class TimeRangeTest {

  private static final Instant EIGHT_AM = Instant.parse("2026-09-01T08:00:00Z");
  private static final Instant FOUR_PM = Instant.parse("2026-09-01T16:00:00Z");
  private static final Instant LATER = Instant.parse("2026-09-01T20:00:00Z");

  @Test
  void GivenAnEndAfterItsStart_WhenConstructingARange_ThenItsLengthIsThatSpan() {
    TimeRange period = new TimeRange(EIGHT_AM, FOUR_PM);

    assertThat(period.length()).isEqualTo(Duration.ofHours(8));
  }

  @Test
  void GivenAnEndBeforeItsStart_WhenConstructingARange_ThenItIsRejected() {
    assertThatThrownBy(() -> new TimeRange(FOUR_PM, EIGHT_AM))
        .isInstanceOf(InvalidTimeRangeException.class)
        .hasMessageContaining("must end after it starts");
  }

  @Test
  void GivenAnEndEqualToItsStart_WhenConstructingARange_ThenItIsRejected() {
    assertThatThrownBy(() -> new TimeRange(EIGHT_AM, EIGHT_AM))
        .isInstanceOf(InvalidTimeRangeException.class);
  }

  @Test
  void GivenTwoRangesSharingAnInstant_WhenAskedIfTheyOverlap_ThenTheyDo() {
    TimeRange morning = new TimeRange(EIGHT_AM, FOUR_PM);
    TimeRange afternoon = new TimeRange(Instant.parse("2026-09-01T12:00:00Z"), LATER);

    assertThat(morning.overlaps(afternoon)).isTrue();
    assertThat(afternoon.overlaps(morning)).isTrue();
  }

  @Test
  void GivenTwoSeparatedRanges_WhenAskedIfTheyOverlap_ThenTheyDoNot() {
    TimeRange morning = new TimeRange(EIGHT_AM, FOUR_PM);
    TimeRange evening = new TimeRange(LATER, Instant.parse("2026-09-02T02:00:00Z"));

    assertThat(morning.overlaps(evening)).isFalse();
    assertThat(morning.gapTo(evening)).isEqualTo(Duration.ofHours(4));
    assertThat(evening.gapTo(morning)).isEqualTo(Duration.ofHours(4));
  }

  @Test
  void GivenTwoOverlappingRanges_WhenAskedForTheGap_ThenItIsZero() {
    TimeRange morning = new TimeRange(EIGHT_AM, FOUR_PM);
    TimeRange overlapping = new TimeRange(Instant.parse("2026-09-01T12:00:00Z"), LATER);

    assertThat(morning.gapTo(overlapping)).isEqualTo(Duration.ZERO);
  }

  /**
   * A range is half-open, so one ending at the instant the next starts shares no instant with it:
   * back-to-back shifts do not overlap, and nothing separates them.
   */
  @Test
  void GivenARangeEndingWhereTheNextStarts_WhenComparingThem_ThenTheyDoNotOverlapAndHaveNoGap() {
    TimeRange day = new TimeRange(EIGHT_AM, FOUR_PM);
    TimeRange evening = new TimeRange(FOUR_PM, LATER);

    assertThat(day.overlaps(evening)).isFalse();
    assertThat(evening.overlaps(day)).isFalse();
    assertThat(day.gapTo(evening)).isEqualTo(Duration.ZERO);
    assertThat(evening.gapTo(day)).isEqualTo(Duration.ZERO);
  }

  @Test
  void GivenANullBoundary_WhenConstructingARange_ThenItIsRejected() {
    assertThatThrownBy(() -> new TimeRange(null, FOUR_PM)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new TimeRange(EIGHT_AM, null))
        .isInstanceOf(NullPointerException.class);
  }
}
