package io.github.naimelijah.hrsched.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Specification of the one invariant the domain currently enforces. */
class ShiftPeriodTest {

  private static final Instant EIGHT_AM = Instant.parse("2026-09-01T08:00:00Z");
  private static final Instant FOUR_PM = Instant.parse("2026-09-01T16:00:00Z");

  @Test
  void GivenAnEndAfterItsStart_WhenConstructingAPeriod_ThenItsLengthIsThatSpan() {
    ShiftPeriod period = new ShiftPeriod(EIGHT_AM, FOUR_PM);

    assertThat(period.length()).isEqualTo(Duration.ofHours(8));
  }

  @Test
  void GivenAnEndBeforeItsStart_WhenConstructingAPeriod_ThenItIsRejected() {
    assertThatThrownBy(() -> new ShiftPeriod(FOUR_PM, EIGHT_AM))
        .isInstanceOf(InvalidShiftPeriodException.class)
        .hasMessageContaining("must end after it starts");
  }

  @Test
  void GivenAnEndEqualToItsStart_WhenConstructingAPeriod_ThenItIsRejected() {
    assertThatThrownBy(() -> new ShiftPeriod(EIGHT_AM, EIGHT_AM))
        .isInstanceOf(InvalidShiftPeriodException.class);
  }

  @Test
  void GivenANullBoundary_WhenConstructingAPeriod_ThenItIsRejected() {
    assertThatThrownBy(() -> new ShiftPeriod(null, FOUR_PM))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new ShiftPeriod(EIGHT_AM, null))
        .isInstanceOf(NullPointerException.class);
  }
}
